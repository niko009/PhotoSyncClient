using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using Xunit;

namespace PhotoSync.Server.Tests;

public sealed class ApiTests
{
    [Fact]
    public async Task ServerInfo_ReturnsConfiguredMetadata()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/api/server/info");

        response.EnsureSuccessStatusCode();
        var payload = await response.Content.ReadFromJsonAsync<ServerInfoResponse>();

        Assert.NotNull(payload);
        Assert.Equal("Test PhotoSync", payload.ServerName);
        Assert.Equal("ok", payload.Status);
        Assert.Equal("", payload.StorageRoot);
    }

    [Fact]
    public async Task CapabilitiesAdvertiseImplementedFamilySharing()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();

        var payload = await client.GetFromJsonAsync<JsonElement>("/api/server/capabilities");

        Assert.True(payload.GetProperty("family_sharing").GetBoolean());
    }

    [Fact]
    public async Task RegisterAndAlbumLifecycle_CreatesReadableAlbumFolder()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();

        var deviceUuid = Guid.NewGuid();
        await RegisterDeviceAsync(client, deviceUuid, "Samsung S24");

        var createResponse = await client.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(deviceUuid, "Family 2026"));
        createResponse.EnsureSuccessStatusCode();

        var createdAlbum = await createResponse.Content.ReadFromJsonAsync<CreateAlbumResponse>();
        Assert.NotNull(createdAlbum);
        Assert.True(createdAlbum.Created);
        Assert.Equal("Samsung_S24_local/Family_2026", createdAlbum.ServerFolderPath);

        var listResponse = await client.GetAsync($"/api/albums?device_uuid={deviceUuid}");
        listResponse.EnsureSuccessStatusCode();
        var albums = await listResponse.Content.ReadFromJsonAsync<AlbumsResponse>();

        Assert.NotNull(albums);
        Assert.Single(albums.Albums);
        Assert.Equal(createdAlbum.ServerFolderPath, albums.Albums[0].ServerFolderPath);
        Assert.True(Directory.Exists(Path.Combine(factory.StoragePath, createdAlbum.ServerFolderPath.Replace('/', Path.DirectorySeparatorChar))));
    }

    [Fact]
    public async Task UploadFlow_CheckUploadCheck_StoresFileAndMetadata()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();

        var deviceUuid = Guid.NewGuid();
        var deviceId = await RegisterDeviceAsync(client, deviceUuid, "Pixel 9");
        await client.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(deviceUuid, "Trips"));

        var content = $"photo-bytes-for-mvp-{Guid.NewGuid():N}";
        var bytes = Encoding.UTF8.GetBytes(content);
        var sha256 = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

        var precheck = await client.PostAsJsonAsync("/api/files/check", new FileCheckRequest(deviceUuid, "Trips", "IMG_0001.jpg", bytes.Length, sha256));
        var precheckPayload = await precheck.Content.ReadFromJsonAsync<FileCheckResponse>();
        Assert.NotNull(precheckPayload);
        Assert.False(precheckPayload.Exists);

        using var form = new MultipartFormDataContent
        {
            { new StringContent(deviceUuid.ToString()), "device_uuid" },
            { new StringContent("Trips"), "album_name" },
            { new StringContent("IMG_0001.jpg"), "original_name" },
            { new StringContent("image/jpeg"), "mime_type" },
            { new StringContent(bytes.Length.ToString()), "size_bytes" },
            { new StringContent(sha256), "sha256" },
            { new StringContent("2026-06-04T13:25:11Z"), "created_at" },
            { new StringContent("4032"), "width" },
            { new StringContent("3024"), "height" },
            { new StringContent("false"), "is_video" },
            { new ByteArrayContent(bytes), "file", "IMG_0001.jpg" }
        };

        var uploadResponse = await client.PostAsync("/api/files/upload", form);
        Assert.Equal(HttpStatusCode.Created, uploadResponse.StatusCode);

        var uploadPayload = await uploadResponse.Content.ReadFromJsonAsync<UploadFileResponse>();
        Assert.NotNull(uploadPayload);
        Assert.Matches("^Pixel_9_local/Trips/IMG_0001\\.jpg$", uploadPayload.RelativePath);

        var absoluteFilePath = Path.Combine(factory.StoragePath, uploadPayload.RelativePath.Replace('/', Path.DirectorySeparatorChar));
        Assert.True(File.Exists(absoluteFilePath));
        Assert.Equal(bytes, await File.ReadAllBytesAsync(absoluteFilePath));

        var postCheck = await client.PostAsJsonAsync("/api/files/check", new FileCheckRequest(deviceUuid, "Trips", "IMG_0001.jpg", bytes.Length, sha256));
        var postCheckPayload = await postCheck.Content.ReadFromJsonAsync<FileCheckResponse>();
        Assert.NotNull(postCheckPayload);
        Assert.True(postCheckPayload.Exists);
        Assert.Equal(uploadPayload.ServerFileId, postCheckPayload.ServerFileId);

        var fileListResponse = await client.GetAsync($"/api/files/device/{deviceId}");
        fileListResponse.EnsureSuccessStatusCode();
        var fileListPayload = await fileListResponse.Content.ReadFromJsonAsync<FileListResponse>();
        Assert.NotNull(fileListPayload);
        var serverFile = Assert.Single(fileListPayload.Files);
        Assert.Equal(uploadPayload.ServerFileId, serverFile.ServerFileId);
        Assert.Equal("Trips", serverFile.AlbumName);
        Assert.Equal("IMG_0001.jpg", serverFile.OriginalName);
        Assert.Equal($"/api/files/{uploadPayload.ServerFileId}/preview", serverFile.PreviewUrl);
        Assert.Equal($"/api/files/{uploadPayload.ServerFileId}/download", serverFile.DownloadUrl);

        var previewResponse = await client.GetAsync(serverFile.PreviewUrl);
        previewResponse.EnsureSuccessStatusCode();
        Assert.Equal(bytes, await previewResponse.Content.ReadAsByteArrayAsync());

        var downloadResponse = await client.GetAsync(serverFile.DownloadUrl);
        downloadResponse.EnsureSuccessStatusCode();
        Assert.Equal(bytes, await downloadResponse.Content.ReadAsByteArrayAsync());

        Assert.True(Directory.Exists(Path.Combine(factory.StoragePath, "_temp")));
        Assert.Empty(Directory.GetFiles(Path.Combine(factory.StoragePath, "_temp")));

        await using var scope = factory.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<PhotoSyncDbContext>();
        Assert.Equal(1, await db.Files.CountAsync(x => x.Sha256 == sha256));
    }

    [Fact]
    public async Task UploadHashMismatch_ReturnsBadRequestAndDoesNotCommit()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();

        var deviceUuid = Guid.NewGuid();
        await RegisterDeviceAsync(client, deviceUuid, "Pixel 9");
        await client.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(deviceUuid, "Trips"));

        var bytes = Encoding.UTF8.GetBytes("bad-hash-upload");

        using var form = new MultipartFormDataContent
        {
            { new StringContent(deviceUuid.ToString()), "device_uuid" },
            { new StringContent("Trips"), "album_name" },
            { new StringContent("clip.mp4"), "original_name" },
            { new StringContent("video/mp4"), "mime_type" },
            { new StringContent(bytes.Length.ToString()), "size_bytes" },
            { new StringContent(new string('0', 64)), "sha256" },
            { new StringContent("2026-06-04T13:25:11Z"), "created_at" },
            { new StringContent("true"), "is_video" },
            { new ByteArrayContent(bytes), "file", "clip.mp4" }
        };

        var uploadResponse = await client.PostAsync("/api/files/upload", form);
        Assert.Equal(HttpStatusCode.BadRequest, uploadResponse.StatusCode);

        Assert.True(Directory.Exists(Path.Combine(factory.StoragePath, "_temp")));
        Assert.Empty(Directory.GetFiles(Path.Combine(factory.StoragePath, "_temp")));

        await using var scope = factory.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<PhotoSyncDbContext>();
        Assert.Equal(0, await db.Files.CountAsync(x => x.OriginalName == "clip.mp4"));
    }

    [Fact]
    public async Task ResumableUpload_ResumesAfterInterruption_AndCompletionIsIdempotent()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        var deviceUuid = Guid.NewGuid();
        await RegisterDeviceAsync(client, deviceUuid, "Resume phone");
        var albumResponse = await client.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(deviceUuid, "Resume"));
        var albumId = (await albumResponse.Content.ReadFromJsonAsync<CreateAlbumResponse>())!.AlbumId;
        var bytes = Encoding.UTF8.GetBytes("an interrupted video upload can continue");
        var request = new ResumableUploadRequest(albumId, null, null, "clip.mp4", "video/mp4", bytes.Length,
            Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant(), DateTimeOffset.UtcNow, true);

        var started = await client.PostAsJsonAsync("/api/files/uploads", request);
        started.EnsureSuccessStatusCode();
        var session = (await started.Content.ReadFromJsonAsync<ResumableUploadStatusResponse>())!;
        using (var first = new ByteArrayContent(bytes[..10]))
            (await client.PutAsync($"/api/files/uploads/{session.UploadId}?offset=0", first)).EnsureSuccessStatusCode();

        var afterRestart = await client.GetFromJsonAsync<ResumableUploadStatusResponse>($"/api/files/uploads/{session.UploadId}");
        Assert.NotNull(afterRestart);
        Assert.Equal(10, afterRestart!.ReceivedBytes);
        using (var rest = new ByteArrayContent(bytes[10..]))
            (await client.PutAsync($"/api/files/uploads/{session.UploadId}?offset=10", rest)).EnsureSuccessStatusCode();

        var complete = await client.PostAsJsonAsync($"/api/files/uploads/{session.UploadId}/complete", new { });
        complete.EnsureSuccessStatusCode();
        var result = (await complete.Content.ReadFromJsonAsync<UploadFileResponse>())!;
        var repeated = await client.PostAsJsonAsync($"/api/files/uploads/{session.UploadId}/complete", new { });
        repeated.EnsureSuccessStatusCode();
        Assert.Equal(result.ServerFileId, (await repeated.Content.ReadFromJsonAsync<UploadFileResponse>())!.ServerFileId);
        Assert.Equal(bytes, await client.GetByteArrayAsync($"/api/files/{result.ServerFileId}/download"));
    }

    [Fact]
    public async Task ResumableUpload_DiscardsUncommittedTailAndRetriesWithoutDuplicate()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        var uuid = Guid.NewGuid();
        await RegisterDeviceAsync(client, uuid, "Retry phone");
        var album = (await (await client.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(uuid, "Retry")))
            .Content.ReadFromJsonAsync<CreateAlbumResponse>())!;
        var bytes = Encoding.UTF8.GetBytes("verified media after a disconnected chunk");
        var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        var metadata = new ResumableUploadRequest(album.AlbumId, null, null, "clip.mp4", "video/mp4",
            bytes.Length, hash, DateTimeOffset.UtcNow, true);
        var start = (await (await client.PostAsJsonAsync("/api/files/uploads", metadata)).Content
            .ReadFromJsonAsync<ResumableUploadStatusResponse>())!;
        using (var first = new ByteArrayContent(bytes[..8]))
            (await client.PutAsync($"/api/files/uploads/{start.UploadId}?offset=0", first)).EnsureSuccessStatusCode();
        var temp = Path.Combine(factory.StoragePath, "_temp", $"resume-{start.UploadId:N}.part");
        await using (var tail = new FileStream(temp, FileMode.Append, FileAccess.Write))
            await tail.WriteAsync(new byte[] { 1, 2, 3 }); // bytes written before a lost connection/DB commit
        using (var rest = new ByteArrayContent(bytes[8..]))
            (await client.PutAsync($"/api/files/uploads/{start.UploadId}?offset=8", rest)).EnsureSuccessStatusCode();
        var completed = (await (await client.PostAsJsonAsync($"/api/files/uploads/{start.UploadId}/complete", new { }))
            .Content.ReadFromJsonAsync<UploadFileResponse>())!;
        Assert.Equal(bytes, await client.GetByteArrayAsync($"/api/files/{completed.ServerFileId}/download"));
        var duplicate = (await (await client.PostAsJsonAsync("/api/files/uploads", metadata)).Content
            .ReadFromJsonAsync<ResumableUploadStatusResponse>())!;
        Assert.Equal(completed.ServerFileId, duplicate.CompletedFile?.ServerFileId);
        await using var scope = factory.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<PhotoSyncDbContext>();
        Assert.Equal(1, await db.Files.CountAsync(x => x.Sha256 == hash));
    }

    [Fact]
    public async Task MultipartUpload_RejectsTruncatedBodyWithoutCreatingFile()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        var uuid = Guid.NewGuid();
        await RegisterDeviceAsync(client, uuid, "Multipart phone");
        (await client.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(uuid, "Broken"))).EnsureSuccessStatusCode();
        using var body = new ByteArrayContent(Encoding.UTF8.GetBytes(
            "--test-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"bad.jpg\"\r\n\r\npartial"));
        body.Headers.ContentType = System.Net.Http.Headers.MediaTypeHeaderValue.Parse("multipart/form-data; boundary=test-boundary");
        var response = await client.PostAsync("/api/files/upload", body);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        await using var scope = factory.Services.CreateAsyncScope();
        Assert.Equal(0, await scope.ServiceProvider.GetRequiredService<PhotoSyncDbContext>().Files.CountAsync());
    }

    [Theory]
    [InlineData(8 * 1024 * 1024, "image/x-adobe-dng", false)]
    [InlineData(32 * 1024 * 1024, "video/mp4", true)]
    public async Task ResumableUpload_LargeMediaStreamsAndVerifiesHash(int size, string mime, bool video)
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        var uuid = Guid.NewGuid();
        await RegisterDeviceAsync(client, uuid, "Large media phone");
        var album = (await (await client.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(uuid, "Large")))
            .Content.ReadFromJsonAsync<CreateAlbumResponse>())!;
        var bytes = new byte[size];
        RandomNumberGenerator.Fill(bytes);
        var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        var metadata = new ResumableUploadRequest(album.AlbumId, null, null, video ? "long.mp4" : "raw.dng",
            mime, size, hash, DateTimeOffset.UtcNow, video);
        var start = (await (await client.PostAsJsonAsync("/api/files/uploads", metadata)).Content
            .ReadFromJsonAsync<ResumableUploadStatusResponse>())!;
        for (var offset = 0; offset < size; offset += 4 * 1024 * 1024)
        {
            using var chunk = new ByteArrayContent(bytes, offset, Math.Min(4 * 1024 * 1024, size - offset));
            (await client.PutAsync($"/api/files/uploads/{start.UploadId}?offset={offset}", chunk)).EnsureSuccessStatusCode();
        }
        var response = await client.PostAsJsonAsync($"/api/files/uploads/{start.UploadId}/complete", new { });
        response.EnsureSuccessStatusCode();
        var completed = (await response.Content.ReadFromJsonAsync<UploadFileResponse>())!;
        var storedPath = Path.Combine(factory.StoragePath, completed.RelativePath.Replace('/', Path.DirectorySeparatorChar));
        Assert.Equal(size, new FileInfo(storedPath).Length);
        await using var stored = File.OpenRead(storedPath);
        Assert.Equal(hash, Convert.ToHexString(await SHA256.HashDataAsync(stored)).ToLowerInvariant());
    }

    private static async Task<int> RegisterDeviceAsync(HttpClient client, Guid deviceUuid, string deviceName)
    {
        client.DefaultRequestHeaders.Remove("X-PhotoSync-Device");
        client.DefaultRequestHeaders.Add("X-PhotoSync-Device", deviceUuid.ToString());
        client.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", Convert.ToHexString(RandomNumberGenerator.GetBytes(32)));
        var response = await client.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(deviceUuid, deviceName, "0.1.0"));
        response.EnsureSuccessStatusCode();
        var payload = await response.Content.ReadFromJsonAsync<RegisterDeviceResponse>();
        Assert.NotNull(payload);
        return payload.DeviceId;
    }
}

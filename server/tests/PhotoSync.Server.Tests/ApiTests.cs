using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
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
        Assert.Equal(factory.StoragePath, payload.StorageRoot);
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
        Assert.Contains("Samsung_S24", createdAlbum.ServerFolderPath);
        Assert.Contains("Family_2026", createdAlbum.ServerFolderPath);

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
        Assert.Contains("/Trips/", uploadPayload.RelativePath);

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

    private static async Task<int> RegisterDeviceAsync(HttpClient client, Guid deviceUuid, string deviceName)
    {
        var response = await client.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(deviceUuid, deviceName, "0.1.0"));
        response.EnsureSuccessStatusCode();
        var payload = await response.Content.ReadFromJsonAsync<RegisterDeviceResponse>();
        Assert.NotNull(payload);
        return payload.DeviceId;
    }
}

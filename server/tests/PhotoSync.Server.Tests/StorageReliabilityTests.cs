using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using Xunit;

namespace PhotoSync.Server.Tests;

public sealed class StorageReliabilityTests
{
    [Fact]
    public async Task DamagedOriginal_IsNotConfirmedByCheckOrRetry()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        var uuid = await Register(client);
        using var form = Upload(uuid);
        var response = await client.PostAsync("/api/files/upload", form);
        response.EnsureSuccessStatusCode();
        var stored = (await response.Content.ReadFromJsonAsync<UploadFileResponse>())!;
        await File.WriteAllBytesAsync(Path.Combine(factory.StoragePath, stored.RelativePath), [4, 3, 2, 1]);
        var hash = Convert.ToHexString(SHA256.HashData(new byte[] { 1, 2, 3, 4 })).ToLowerInvariant();
        var check = await client.PostAsJsonAsync("/api/files/check", new FileCheckRequest(uuid, "Audit", "audit.jpg", 4, hash));
        Assert.False((await check.Content.ReadFromJsonAsync<FileCheckResponse>())!.Exists);
        using var retry = Upload(uuid);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsync("/api/files/upload", retry)).StatusCode);
    }

    [Fact]
    public async Task Download_SupportsByteRanges()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        var uuid = await Register(client);
        using var form = Upload(uuid);
        var response = await client.PostAsync("/api/files/upload", form);
        response.EnsureSuccessStatusCode();
        var stored = (await response.Content.ReadFromJsonAsync<UploadFileResponse>())!;
        using var request = new HttpRequestMessage(HttpMethod.Get, $"/api/files/{stored.ServerFileId}/download");
        request.Headers.Range = new RangeHeaderValue(1, 2);
        var partial = await client.SendAsync(request);
        Assert.Equal(HttpStatusCode.PartialContent, partial.StatusCode);
        Assert.Equal(new byte[] { 2, 3 }, await partial.Content.ReadAsByteArrayAsync());
    }

    [Fact]
    public async Task Journal_DoesNotRecordFamilyTokensOrQuerySecrets()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        await client.PostAsJsonAsync("/api/family/join/synthetic-invite-secret?key=synthetic-query-secret",
            new { id_token = "synthetic-google-secret" });
        var log = string.Join("\n", Directory.GetFiles(Path.Combine(factory.RootPath, "logs"))
            .Select(File.ReadAllText));
        Assert.DoesNotContain("synthetic-invite-secret", log);
        Assert.DoesNotContain("synthetic-query-secret", log);
        Assert.DoesNotContain("synthetic-google-secret", log);
        Assert.Contains("sensitive endpoint", log);
    }

    [Fact]
    public async Task RegistrationWithKnownUuid_CannotReplaceSecret()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var owner = factory.CreateClient();
        var uuid = await Register(owner);
        using var stranger = factory.CreateClient();
        stranger.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", Convert.ToHexString(RandomNumberGenerator.GetBytes(32)));
        stranger.DefaultRequestHeaders.Add("X-PhotoSync-Device", uuid.ToString());
        Assert.Equal(HttpStatusCode.Unauthorized, (await stranger.GetAsync("/api/devices")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await stranger.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(uuid, "Stranger", "audit"))).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await stranger.GetAsync($"/api/albums?device_uuid={uuid}")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await owner.GetAsync("/api/devices")).StatusCode);
    }

    [Fact]
    public async Task MissingOriginal_RetryFailsClosed()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        var uuid = await Register(client);
        using var first = Upload(uuid);
        var response = await client.PostAsync("/api/files/upload", first);
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        var stored = (await response.Content.ReadFromJsonAsync<UploadFileResponse>())!;
        // Delete only this probe's synthetic original in its unique temp directory.
        File.Delete(Path.Combine(factory.StoragePath, stored.RelativePath));
        using var retry = Upload(uuid);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsync("/api/files/upload", retry)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound,
            (await client.GetAsync($"/api/files/{stored.ServerFileId}/download")).StatusCode);
    }

    [Fact]
    public async Task DatabaseInsertFailure_RetryReusesUncommittedOriginal()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        var uuid = await Register(client);
        await using var scope = factory.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<PhotoSyncDbContext>();
        await db.Database.ExecuteSqlRawAsync("CREATE TRIGGER audit_fail BEFORE INSERT ON files BEGIN SELECT RAISE(ABORT, 'audit injected failure'); END;");
        using var failed = Upload(uuid);
        Assert.Equal(HttpStatusCode.InternalServerError,
            (await client.PostAsync("/api/files/upload", failed)).StatusCode);
        Assert.Equal(0, await db.Files.IgnoreQueryFilters().CountAsync());
        Assert.Single(Directory.GetFiles(factory.StoragePath, "*.jpg", SearchOption.AllDirectories));
        await db.Database.ExecuteSqlRawAsync("DROP TRIGGER audit_fail;");
        using var retry = Upload(uuid);
        Assert.Equal(HttpStatusCode.Created, (await client.PostAsync("/api/files/upload", retry)).StatusCode);
        Assert.Equal(1, await db.Files.IgnoreQueryFilters().CountAsync());
        Assert.Single(Directory.GetFiles(factory.StoragePath, "*.jpg", SearchOption.AllDirectories));
    }

    private static async Task<Guid> Register(HttpClient client)
    {
        var uuid = Guid.NewGuid();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", Convert.ToHexString(RandomNumberGenerator.GetBytes(32)));
        client.DefaultRequestHeaders.Add("X-PhotoSync-Device", uuid.ToString());
        (await client.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(uuid, "Audit", "audit"))).EnsureSuccessStatusCode();
        (await client.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(uuid, "Audit"))).EnsureSuccessStatusCode();
        return uuid;
    }

    private static MultipartFormDataContent Upload(Guid uuid)
    {
        byte[] bytes = [1, 2, 3, 4];
        return new MultipartFormDataContent
        {
            { new StringContent(uuid.ToString()), "device_uuid" },
            { new StringContent("Audit"), "album_name" },
            { new StringContent("audit.jpg"), "original_name" },
            { new StringContent("image/jpeg"), "mime_type" },
            { new StringContent(bytes.Length.ToString()), "size_bytes" },
            { new StringContent(Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant()), "sha256" },
            { new StringContent("2026-09-07T10:00:00Z"), "created_at" },
            { new ByteArrayContent(bytes), "file", "audit.jpg" }
        };
    }
}


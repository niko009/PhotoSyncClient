// Diagnostic probes: these assertions describe defects observed on 2026-09-07,
// not the desired production behavior. Copy into the active test project to run.
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

public sealed class ProductionReadinessProbes
{
    [Fact]
    public async Task RegistrationWithKnownUuid_ReplacesSecretWithoutProofOfOwnership()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var owner = factory.CreateClient();
        var uuid = await Register(owner);
        using var stranger = factory.CreateClient();
        stranger.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", Convert.ToHexString(RandomNumberGenerator.GetBytes(32)));
        stranger.DefaultRequestHeaders.Add("X-PhotoSync-Device", uuid.ToString());
        Assert.Equal(HttpStatusCode.Unauthorized, (await stranger.GetAsync("/api/devices")).StatusCode);
        (await stranger.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(uuid, "Stranger", "audit"))).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.OK, (await stranger.GetAsync($"/api/albums?device_uuid={uuid}")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await owner.GetAsync("/api/devices")).StatusCode);
    }

    [Fact]
    public async Task MissingOriginal_RetryReportsSuccessButDownloadStillFails()
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
        Assert.Equal(HttpStatusCode.OK, (await client.PostAsync("/api/files/upload", retry)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound,
            (await client.GetAsync($"/api/files/{stored.ServerFileId}/download")).StatusCode);
    }

    [Fact]
    public async Task DatabaseInsertFailure_LeavesUntrackedOriginalAndRetryCreatesSecondCopy()
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
        Assert.Equal(2, Directory.GetFiles(factory.StoragePath, "*.jpg", SearchOption.AllDirectories).Length);
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

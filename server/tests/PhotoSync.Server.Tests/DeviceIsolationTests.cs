using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Models;
using Xunit;

namespace PhotoSync.Server.Tests;

public sealed class DeviceIsolationTests
{
    private static void Authenticate(HttpClient client, Guid uuid, string secret)
    {
        client.DefaultRequestHeaders.Remove("X-PhotoSync-Device");
        client.DefaultRequestHeaders.Add("X-PhotoSync-Device", uuid.ToString());
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", secret);
    }

    private static async Task<int> Register(HttpClient client, Guid uuid, string secret)
    {
        Authenticate(client, uuid, secret);
        var response = await client.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(uuid, "Phone", "0.2.0"));
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<RegisterDeviceResponse>())!.DeviceId;
    }

    private static MultipartFormDataContent Upload(Guid uuid, string album = "Private")
    {
        var bytes = Encoding.UTF8.GetBytes("identical-photo-content");
        return new MultipartFormDataContent
        {
            { new StringContent(uuid.ToString()), "device_uuid" },
            { new StringContent(album), "album_name" },
            { new StringContent("photo.jpg"), "original_name" },
            { new StringContent("image/jpeg"), "mime_type" },
            { new StringContent(bytes.Length.ToString()), "size_bytes" },
            { new StringContent(Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant()), "sha256" },
            { new StringContent("2026-08-31T12:00:00Z"), "created_at" },
            { new ByteArrayContent(bytes), "file", "photo.jpg" }
        };
    }

    [Theory]
    [InlineData("/api/devices")]
    [InlineData("/api/stats/summary")]
    [InlineData("/api/files/1/download")]
    [InlineData("/api/files/1/preview")]
    [InlineData("/api/admin/dashboard")]
    [InlineData("/admin")]
    public async Task DataRequiresSecret(string path)
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync(path)).StatusCode);
    }

    [Fact]
    public async Task TwoDevicesCannotSeeOrWriteEachOthersData_AndCanStoreSameHash()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var a = factory.CreateClient();
        using var b = factory.CreateClient();
        var uuidA = Guid.NewGuid(); var uuidB = Guid.NewGuid();
        var secretA = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        var secretB = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        var idA = await Register(a, uuidA, secretA);
        var idB = await Register(b, uuidB, secretB);
        (await a.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(uuidA, "Private"))).EnsureSuccessStatusCode();
        using var formA = Upload(uuidA);
        var responseA = await a.PostAsync("/api/files/upload", formA);
        responseA.EnsureSuccessStatusCode();
        var fileA = (await responseA.Content.ReadFromJsonAsync<UploadFileResponse>())!;
        foreach (var action in new[] { "preview", "download" })
            Assert.Equal(HttpStatusCode.NotFound, (await b.GetAsync($"/api/files/{fileA.ServerFileId}/{action}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await b.GetAsync($"/api/files/device/{idA}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await b.GetAsync($"/api/albums?device_uuid={uuidA}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await b.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(uuidA, "Stolen"))).StatusCode);
        using var maliciousUpload = Upload(uuidA);
        Assert.Equal(HttpStatusCode.NotFound, (await b.PostAsync("/api/files/upload", maliciousUpload)).StatusCode);
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("identical-photo-content"))).ToLowerInvariant();
        var check = await b.PostAsJsonAsync("/api/files/check", new FileCheckRequest(uuidA, "Private", "photo.jpg", 23, hash));
        Assert.False((await check.Content.ReadFromJsonAsync<FileCheckResponse>())!.Exists);
        var summary = await b.GetFromJsonAsync<JsonElement>("/api/stats/summary");
        Assert.Equal(0, summary.GetProperty("file_count").GetInt32());
        var devices = await b.GetFromJsonAsync<JsonElement>("/api/devices");
        Assert.Equal(idB, Assert.Single(devices.GetProperty("devices").EnumerateArray()).GetProperty("id").GetInt32());
        // A device token is not a browser administrator credential.
        Assert.Equal(HttpStatusCode.Unauthorized, (await b.GetAsync("/api/admin/dashboard")).StatusCode);

        (await b.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(uuidB, "Private"))).EnsureSuccessStatusCode();
        using var formB = Upload(uuidB);
        var responseB = await b.PostAsync("/api/files/upload", formB);
        responseB.EnsureSuccessStatusCode();
        Assert.NotEqual(fileA.ServerFileId, (await responseB.Content.ReadFromJsonAsync<UploadFileResponse>())!.ServerFileId);
        Authenticate(b, uuidA, secretB);
        Assert.Equal(HttpStatusCode.Unauthorized, (await b.GetAsync("/api/devices")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await b.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(uuidA, "Hijack", "1"))).StatusCode);
        Assert.Equal(idA, await Register(a, uuidA, secretA));
    }

    [Fact]
    public async Task FactoryUsesOnlyItsOwnDatabase()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        await using var scope = factory.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<PhotoSyncDbContext>();
        Assert.Equal(factory.DatabasePath, db.Database.GetDbConnection().DataSource);
        Assert.Equal(0, await db.Devices.IgnoreQueryFilters().CountAsync());
    }

    [Fact]
    public async Task LegacyUuidCannotBeClaimedAndUpgradeIsRepeatable()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();
        var uuid = Guid.NewGuid();
        await using (var scope = factory.Services.CreateAsyncScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<PhotoSyncDbContext>();
            db.Devices.Add(new DeviceEntity { DeviceUuid = uuid, DeviceName = "Legacy", AppVersion = "1", StorageFolderName = "legacy" });
            await db.SaveChangesAsync();
            // Recreate the legacy schema shape inside this isolated test database.
            await db.Database.ExecuteSqlRawAsync("""
                DROP TABLE device_credentials;
                DROP INDEX IX_files_DeviceId_AlbumId_Sha256;
                CREATE UNIQUE INDEX IX_files_Sha256 ON files (Sha256);
                """);
            await DeviceSecuritySchema.InitializeAsync(db);
            await DeviceSecuritySchema.InitializeAsync(db);
            Assert.Equal(1, await db.Devices.CountAsync());
        }
        Authenticate(client, uuid, new string('a', 64));
        var claim = await client.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(uuid, "Hijack", "1"));
        Assert.Equal(HttpStatusCode.Unauthorized, claim.StatusCode);
    }
}

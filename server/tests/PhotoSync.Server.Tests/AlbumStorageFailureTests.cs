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

public sealed class AlbumStorageFailureTests
{
    [Fact]
    public async Task CreateAlbum_RollsBackDatabase_WhenStorageCannotCreateDirectory()
    {
        var externalRoot = Path.Combine(Path.GetTempPath(), "photosync-storage-failure", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(externalRoot);
        var storageRoot = Path.Combine(externalRoot, "storage-is-a-file");
        await File.WriteAllTextAsync(storageRoot, "not a directory");

        try
        {
            await using var factory = new TestPhotoSyncFactory(
                new Dictionary<string, string?> { ["PhotoSync:StorageRoot"] = storageRoot });
            using var client = factory.CreateClient();

            var deviceUuid = Guid.NewGuid();
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue(
                "Bearer",
                Convert.ToHexString(RandomNumberGenerator.GetBytes(32)));
            client.DefaultRequestHeaders.Add("X-PhotoSync-Device", deviceUuid.ToString());

            var register = await client.PostAsJsonAsync(
                "/api/devices/register",
                new RegisterDeviceRequest(deviceUuid, "Storage test phone", "0.6.1-beta"));
            register.EnsureSuccessStatusCode();

            var create = await client.PostAsJsonAsync(
                "/api/albums",
                new CreateAlbumRequest(deviceUuid, "Must not become a ghost"));

            Assert.Equal(HttpStatusCode.ServiceUnavailable, create.StatusCode);

            await using var scope = factory.Services.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<PhotoSyncDbContext>();
            Assert.False(await db.Albums.IgnoreQueryFilters().AnyAsync(x => x.AlbumName == "Must not become a ghost"));
        }
        finally
        {
            if (Directory.Exists(externalRoot))
                Directory.Delete(externalRoot, recursive: true);
        }
    }
}

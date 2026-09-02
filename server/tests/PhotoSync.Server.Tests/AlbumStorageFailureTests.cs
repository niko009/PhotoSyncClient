using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Services;
using Xunit;

namespace PhotoSync.Server.Tests;

public sealed class AlbumStorageFailureTests
{
    [Fact]
    public async Task CreateAlbum_RollsBackDatabase_WhenStorageCannotCreateDirectory()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();

        var deviceUuid = Guid.NewGuid();
        const string deviceName = "Storage test phone";
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue(
            "Bearer",
            Convert.ToHexString(RandomNumberGenerator.GetBytes(32)));
        client.DefaultRequestHeaders.Add("X-PhotoSync-Device", deviceUuid.ToString());

        var register = await client.PostAsJsonAsync(
            "/api/devices/register",
            new RegisterDeviceRequest(deviceUuid, deviceName, "0.6.1-beta"));
        register.EnsureSuccessStatusCode();

        var deviceFolder = Path.Combine(
            factory.StoragePath,
            StoragePathResolver.MakeDeviceOwnerFolderName(deviceName, null));
        await File.WriteAllTextAsync(deviceFolder, "block album directory creation");

        var create = await client.PostAsJsonAsync(
            "/api/albums",
            new CreateAlbumRequest(deviceUuid, "Must not become a ghost"));

        Assert.Equal(HttpStatusCode.ServiceUnavailable, create.StatusCode);

        await using var scope = factory.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<PhotoSyncDbContext>();
        Assert.False(await db.Albums.IgnoreQueryFilters().AnyAsync(x => x.AlbumName == "Must not become a ghost"));
    }
}

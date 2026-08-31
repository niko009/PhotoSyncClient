using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using PhotoSync.Server.Contracts;
using Xunit;

namespace PhotoSync.Server.Tests;

public sealed class CapacityTests
{
    private static Task<HttpResponseMessage> Register(HttpClient client, Guid uuid)
    {
        client.DefaultRequestHeaders.Remove("X-PhotoSync-Device");
        client.DefaultRequestHeaders.Add("X-PhotoSync-Device", uuid.ToString());
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", new string('c', 64));
        return client.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(uuid, "Phone", "0.2.0"));
    }

    [Fact]
    public async Task DeviceCapRejectsNewPhonesButNotExistingOnes()
    {
        await using var factory = new TestPhotoSyncFactory(new Dictionary<string, string?> { ["PhotoSync:MaxDevices"] = "1" });
        using var client = factory.CreateClient(); var first = Guid.NewGuid();
        (await Register(client, first)).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.Forbidden, (await Register(client, Guid.NewGuid())).StatusCode);
        (await Register(client, first)).EnsureSuccessStatusCode();
    }

    [Theory]
    [InlineData("PhotoSync:MaxFileBytes", "4")]
    [InlineData("PhotoSync:MaxStorageBytes", "4")]
    [InlineData("PhotoSync:MinFreeDiskBytes", "9223372036854775807")]
    public async Task CapacityFailureDoesNotStoreFiles(string option, string value)
    {
        await using var factory = new TestPhotoSyncFactory(new Dictionary<string, string?> { [option] = value });
        using var client = factory.CreateClient(); var uuid = Guid.NewGuid();
        (await Register(client, uuid)).EnsureSuccessStatusCode();
        (await client.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(uuid, "Test"))).EnsureSuccessStatusCode();
        var payload = new byte[16];
        using var form = new MultipartFormDataContent
        {
            { new StringContent(uuid.ToString()), "device_uuid" }, { new StringContent("Test"), "album_name" },
            { new StringContent("file.png"), "original_name" }, { new StringContent("image/png"), "mime_type" },
            { new StringContent("16"), "size_bytes" }, { new StringContent(Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant()), "sha256" },
            { new StringContent("2026-08-31T12:00:00Z"), "created_at" }, { new ByteArrayContent(payload), "file", "file.png" }
        };
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsync("/api/files/upload", form)).StatusCode);
        Assert.Empty(Directory.GetFiles(factory.StoragePath, "*", SearchOption.AllDirectories));
    }
}

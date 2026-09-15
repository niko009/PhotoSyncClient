using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Security;
using Xunit;

namespace PhotoSync.Server.Tests;

public sealed class GoogleAuthTests
{
    [Fact]
    public async Task VerifiedGoogleAccountLinksDevices_AndSignOutReturnsToDeviceIsolation()
    {
        var verifier = new FakeGoogleVerifier();
        await using var factory = new TestPhotoSyncFactory(googleVerifier: verifier);
        using var first = factory.CreateClient();
        using var second = factory.CreateClient();
        var (firstId, firstUuid) = await Register(first);
        var (secondId, _) = await Register(second);

        Assert.Equal(HttpStatusCode.Unauthorized,
            (await first.PostAsJsonAsync("/api/auth/google/sign-in", new { id_token = "invalid" })).StatusCode);
        (await first.PostAsJsonAsync("/api/auth/google/sign-in", new { id_token = "first" })).EnsureSuccessStatusCode();
        var albumResponse = await first.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(firstUuid, "Shared"));
        albumResponse.EnsureSuccessStatusCode();
        var album = (await albumResponse.Content.ReadFromJsonAsync<CreateAlbumResponse>())!;
        Assert.Equal("Phone_Family_User/Shared",
            album.ServerFolderPath);
        var original = System.Text.Encoding.UTF8.GetBytes("cloud-photo");
        using (var upload = new MultipartFormDataContent
        {
            { new StringContent(album.AlbumId.ToString()), "album_id" },
            { new StringContent("cloud.jpg"), "original_name" },
            { new StringContent("image/jpeg"), "mime_type" },
            { new StringContent(original.Length.ToString()), "size_bytes" },
            { new StringContent(Convert.ToHexString(SHA256.HashData(original)).ToLowerInvariant()), "sha256" },
            { new StringContent("2026-09-15T10:00:00Z"), "created_at" },
            { new ByteArrayContent(original), "file", "cloud.jpg" },
        })
        {
            (await first.PostAsync("/api/files/upload", upload)).EnsureSuccessStatusCode();
        }
        var linked = await second.PostAsJsonAsync("/api/auth/google/sign-in", new { id_token = "second" });
        linked.EnsureSuccessStatusCode();
        Assert.Equal(2, (await linked.Content.ReadFromJsonAsync<GoogleAccountResponse>())!.LinkedDevices);

        var account = await second.GetFromJsonAsync<GoogleAccountResponse>("/api/auth/google/me");
        Assert.Equal("family@example.test", account!.Email);
        var devices = await second.GetFromJsonAsync<JsonElement>("/api/devices");
        Assert.Equal(new[] { firstId, secondId }, devices.GetProperty("devices").EnumerateArray()
            .Select(x => x.GetProperty("id").GetInt32()).Order().ToArray());
        Assert.Equal(HttpStatusCode.OK, (await second.GetAsync($"/api/albums?device_uuid={firstUuid}")).StatusCode);
        var cloudFiles = await second.GetFromJsonAsync<FileListResponse>($"/api/files/album/{album.AlbumId}");
        var cloudFile = Assert.Single(cloudFiles!.Files);
        Assert.Equal("cloud.jpg", cloudFile.OriginalName);
        Assert.Equal(original, await second.GetByteArrayAsync(cloudFile.DownloadUrl));

        (await second.PostAsync("/api/auth/google/sign-out", null)).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.NoContent, (await second.GetAsync("/api/auth/google/me")).StatusCode);
        devices = await second.GetFromJsonAsync<JsonElement>("/api/devices");
        Assert.Equal(secondId, Assert.Single(devices.GetProperty("devices").EnumerateArray()).GetProperty("id").GetInt32());
        Assert.Equal(HttpStatusCode.NotFound, (await second.GetAsync($"/api/albums?device_uuid={firstUuid}")).StatusCode);
    }

    private static async Task<(int Id, Guid Uuid)> Register(HttpClient client)
    {
        var uuid = Guid.NewGuid();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", Convert.ToHexString(RandomNumberGenerator.GetBytes(32)));
        client.DefaultRequestHeaders.Add("X-PhotoSync-Device", uuid.ToString());
        var response = await client.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(uuid, "Phone", "0.3.0"));
        response.EnsureSuccessStatusCode();
        return ((await response.Content.ReadFromJsonAsync<RegisterDeviceResponse>())!.DeviceId, uuid);
    }

    private sealed class FakeGoogleVerifier : IGoogleTokenVerifier
    {
        public Task<VerifiedGoogleIdentity?> VerifyAsync(string idToken, CancellationToken cancellationToken)
            => Task.FromResult<VerifiedGoogleIdentity?>(idToken is "first" or "second"
                ? new("google-subject-1", "family@example.test", "Family User") : null);
    }
}

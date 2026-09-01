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
        (await first.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(firstUuid, "Shared"))).EnsureSuccessStatusCode();
        var linked = await second.PostAsJsonAsync("/api/auth/google/sign-in", new { id_token = "second" });
        linked.EnsureSuccessStatusCode();
        Assert.Equal(2, (await linked.Content.ReadFromJsonAsync<GoogleAccountResponse>())!.LinkedDevices);

        var account = await second.GetFromJsonAsync<GoogleAccountResponse>("/api/auth/google/me");
        Assert.Equal("family@example.test", account!.Email);
        var devices = await second.GetFromJsonAsync<JsonElement>("/api/devices");
        Assert.Equal(new[] { firstId, secondId }, devices.GetProperty("devices").EnumerateArray()
            .Select(x => x.GetProperty("id").GetInt32()).Order().ToArray());
        Assert.Equal(HttpStatusCode.OK, (await second.GetAsync($"/api/albums?device_uuid={firstUuid}")).StatusCode);

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

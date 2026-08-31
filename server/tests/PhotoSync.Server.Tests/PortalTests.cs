using System.Net;
using System.Net.Http.Json;
using System.Net.Http.Headers;
using System.Text.Json;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using PhotoSync.Server.Portal;
using PhotoSync.Server.Contracts;
using Xunit;

namespace PhotoSync.Server.Tests;

public sealed class PortalTests
{
    private const string Password = "Only-Test-Password!923";
    private static HttpClient Client(TestPhotoSyncFactory factory) => factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });

    private static async Task<string> User(TestPhotoSyncFactory factory, string name, string role)
    {
        using var scope = factory.Services.CreateScope();
        var users = scope.ServiceProvider.GetRequiredService<UserManager<PortalUser>>();
        var user = new PortalUser { UserName = name };
        Assert.True((await users.CreateAsync(user, Password)).Succeeded);
        Assert.True((await users.AddToRoleAsync(user, role)).Succeeded);
        return user.Id;
    }

    private static async Task Csrf(HttpClient client)
    {
        var token = await client.GetFromJsonAsync<JsonElement>("/api/portal/csrf");
        client.DefaultRequestHeaders.Remove("X-PhotoSync-CSRF");
        client.DefaultRequestHeaders.Add("X-PhotoSync-CSRF", token.GetProperty("token").GetString());
    }

    private static async Task Login(HttpClient client, string name)
    {
        await Csrf(client);
        var response = await client.PostAsJsonAsync("/api/portal/login", new { userName = name, password = Password });
        response.EnsureSuccessStatusCode();
        var cookie = response.Headers.GetValues("Set-Cookie").Single(x => x.StartsWith("__Host-PhotoSyncPortal="));
        Assert.Contains("secure", cookie); Assert.Contains("httponly", cookie); Assert.Contains("samesite=strict", cookie);
        await Csrf(client);
    }

    [Fact]
    public async Task InitialSetupStatus_DoesNotOfferLoginUntilAccountAndHttpsAreReady()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var secure = Client(factory);
        var initial = await secure.GetFromJsonAsync<JsonElement>("/api/portal/status");
        Assert.False(initial.GetProperty("loginAvailable").GetBoolean());
        await User(factory, "owner", "SuperAdmin");
        var ready = await secure.GetFromJsonAsync<JsonElement>("/api/portal/status");
        Assert.True(ready.GetProperty("loginAvailable").GetBoolean());
        using var plain = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("http://localhost") });
        var untrusted = await plain.GetFromJsonAsync<JsonElement>("/api/portal/status");
        Assert.False(untrusted.GetProperty("loginAvailable").GetBoolean());
        Assert.Equal(HttpStatusCode.ServiceUnavailable, (await plain.GetAsync("/api/portal/csrf")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await plain.GetAsync("/api/portal/admin/dashboard")).StatusCode);
    }

    [Fact]
    public async Task AnonymousAndDeviceCannotReadPortal_AndLoginRequiresCsrf()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = Client(factory);
        foreach (var path in new[] { "/api/portal/me", "/api/portal/dashboard", "/api/portal/admin/dashboard", "/api/portal/admin/users" })
            Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync(path)).StatusCode);
        await User(factory, "owner", "SuperAdmin");
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync("/api/portal/login", new { userName = "owner", password = Password })).StatusCode);
        var uuid = Guid.NewGuid();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", new string('a', 64));
        client.DefaultRequestHeaders.Add("X-PhotoSync-Device", uuid.ToString());
        (await client.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(uuid, "Phone", "0.2.0"))).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/portal/admin/dashboard")).StatusCode);
    }

    [Fact]
    public async Task OwnershipScopesUserDashboard_AndOnlySuperAdminCanAssign()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var owner = Client(factory); using var alice = Client(factory); using var bob = Client(factory); using var admin = Client(factory); using var phone = Client(factory);
        await User(factory, "owner", "SuperAdmin");
        var aliceId = await User(factory, "alice", "User");
        await User(factory, "bob", "User"); await User(factory, "admin", "ServerAdmin");
        var uuid = Guid.NewGuid();
        phone.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", new string('b', 64));
        phone.DefaultRequestHeaders.Add("X-PhotoSync-Device", uuid.ToString());
        var reg = await phone.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(uuid, "Alice Phone", "0.2.0"));
        var device = (await reg.Content.ReadFromJsonAsync<RegisterDeviceResponse>())!.DeviceId;
        (await phone.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(uuid, "Alice private album"))).EnsureSuccessStatusCode();
        var payload = System.Text.Encoding.UTF8.GetBytes("private test file");
        using var form = new MultipartFormDataContent
        {
            { new StringContent(uuid.ToString()), "device_uuid" },
            { new StringContent("Alice private album"), "album_name" },
            { new StringContent("private.txt"), "original_name" },
            { new StringContent("text/plain"), "mime_type" },
            { new StringContent(payload.Length.ToString()), "size_bytes" },
            { new StringContent(Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(payload)).ToLowerInvariant()), "sha256" },
            { new StringContent("2026-08-31T12:00:00Z"), "created_at" },
            { new ByteArrayContent(payload), "file", "private.txt" }
        };
        var upload = await phone.PostAsync("/api/files/upload", form);
        upload.EnsureSuccessStatusCode();
        var fileId = (await upload.Content.ReadFromJsonAsync<UploadFileResponse>())!.ServerFileId;
        await Login(owner, "owner"); await Login(alice, "alice"); await Login(bob, "bob"); await Login(admin, "admin");
        var path = $"/api/portal/admin/devices/{device}/owner";
        Assert.Equal(HttpStatusCode.Forbidden, (await alice.PostAsJsonAsync(path, new { userId = aliceId })).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await admin.PostAsJsonAsync(path, new { userId = aliceId })).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await alice.GetAsync("/api/portal/admin/dashboard")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await admin.GetAsync("/api/portal/admin/users")).StatusCode);
        (await owner.PostAsJsonAsync(path, new { userId = aliceId })).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.Conflict, (await owner.PostAsJsonAsync(path, new { userId = aliceId })).StatusCode);
        var a = await alice.GetFromJsonAsync<JsonElement>("/api/portal/dashboard");
        var b = await bob.GetFromJsonAsync<JsonElement>("/api/portal/dashboard");
        Assert.Single(a.GetProperty("devices").EnumerateArray());
        Assert.Single(a.GetProperty("albums").EnumerateArray());
        Assert.Empty(b.GetProperty("devices").EnumerateArray());
        Assert.Empty(b.GetProperty("albums").EnumerateArray());
        Assert.Single(a.GetProperty("files").EnumerateArray());
        Assert.Empty(b.GetProperty("files").EnumerateArray());
        var original = await alice.GetAsync($"/api/portal/files/{fileId}/download");
        original.EnsureSuccessStatusCode();
        Assert.Equal(payload, await original.Content.ReadAsByteArrayAsync());
        Assert.Equal("application/octet-stream", original.Content.Headers.ContentType!.MediaType);
        Assert.Equal(HttpStatusCode.NotFound, (await owner.GetAsync($"/api/portal/files/{fileId}/download")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await bob.GetAsync($"/api/portal/files/{fileId}/download")).StatusCode);
        var dashboard = await admin.GetFromJsonAsync<JsonElement>("/api/portal/admin/dashboard");
        Assert.Equal(1, dashboard.GetProperty("deviceCount").GetInt32());
        // Browser sessions never grant native API device access.
        Assert.Equal(HttpStatusCode.Unauthorized, (await alice.GetAsync("/api/devices")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await bob.GetAsync("/api/portal/files/1/download")).StatusCode);
        Assert.Single((await owner.GetFromJsonAsync<JsonElement>("/api/portal/admin/audit")).EnumerateArray());
    }

    [Fact]
    public async Task PasswordChangeRevokesExistingSessions_AndWritesRequireCsrf()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var first = Client(factory); using var second = Client(factory);
        await User(factory, "alice", "User"); await Login(first, "alice"); await Login(second, "alice");
        first.DefaultRequestHeaders.Remove("X-PhotoSync-CSRF");
        Assert.Equal(HttpStatusCode.BadRequest, (await first.PostAsJsonAsync("/api/portal/password", new { currentPassword = Password, newPassword = "New-Only-Test!456" })).StatusCode);
        await Csrf(first);
        (await first.PostAsJsonAsync("/api/portal/password", new { currentPassword = Password, newPassword = "New-Only-Test!456" })).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.Unauthorized, (await second.GetAsync("/api/portal/me")).StatusCode);
    }

    [Fact]
    public async Task RepeatedIncorrectPasswordLocksAccount()
    {
        await using var factory = new TestPhotoSyncFactory(); using var client = Client(factory);
        await User(factory, "alice", "User"); await Csrf(client);
        for (var i = 0; i < 5; i++) Assert.Equal(HttpStatusCode.Unauthorized, (await client.PostAsJsonAsync("/api/portal/login", new { userName = "alice", password = "wrong" })).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.PostAsJsonAsync("/api/portal/login", new { userName = "alice", password = Password })).StatusCode);
    }

    [Fact]
    public async Task BootstrapOwnerCanCreateUsers_ButPublicRegistrationAndNewSuperadminsAreForbidden()
    {
        await using var factory = new TestPhotoSyncFactory(new Dictionary<string, string?>
        {
            ["Portal:BootstrapUser"] = "owner", ["Portal:BootstrapPassword"] = Password
        });
        using var owner = Client(factory); using var user = Client(factory); using var anonymous = Client(factory);
        Assert.Equal(HttpStatusCode.Unauthorized, (await anonymous.PostAsJsonAsync("/api/portal/admin/users", new { userName = "evil", password = Password, role = "SuperAdmin" })).StatusCode);
        await Login(owner, "owner");
        (await owner.PostAsJsonAsync("/api/portal/admin/users", new { userName = "alice", password = Password, role = "User" })).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.BadRequest, (await owner.PostAsJsonAsync("/api/portal/admin/users", new { userName = "second-owner", password = Password, role = "SuperAdmin" })).StatusCode);
        await Login(user, "alice");
        Assert.Equal(HttpStatusCode.Forbidden, (await user.GetAsync("/api/portal/admin/users")).StatusCode);
        Assert.Single((await owner.GetFromJsonAsync<JsonElement>("/api/portal/admin/audit")).EnumerateArray());
    }
}

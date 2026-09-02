using System.Net;
using System.Text.Json;
using Xunit;

namespace PhotoSync.Server.Tests;

public sealed class JoinLandingTests
{
    [Fact]
    public async Task AssetLinks_IsAnonymousAndMatchesReleaseCertificate()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/.well-known/assetlinks.json");

        response.EnsureSuccessStatusCode();
        Assert.Equal("application/json", response.Content.Headers.ContentType?.MediaType);
        using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var target = document.RootElement[0].GetProperty("target");
        Assert.Equal("android_app", target.GetProperty("namespace").GetString());
        Assert.Equal("com.photosync.android", target.GetProperty("package_name").GetString());
        Assert.Equal(
            "0D:35:39:A6:6B:59:38:B4:CA:BB:D7:D7:6A:CE:E2:86:87:7A:5E:46:64:8B:4B:8C:6D:42:36:F5:C8:B9:86:2D",
            target.GetProperty("sha256_cert_fingerprints")[0].GetString());
    }

    [Fact]
    public async Task JoinPage_OffersAndroidIntentAndApkFallback()
    {
        await using var factory = new TestPhotoSyncFactory();
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/join/abcdefghijklmnopqrstuvwxyz");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var html = await response.Content.ReadAsStringAsync();
        Assert.Contains("intent://join/abcdefghijklmnopqrstuvwxyz#Intent;scheme=photosync;package=com.photosync.android;", html);
        Assert.Contains("photosync-android-0.6.0-beta.apk", html);
        Assert.Contains("На компьютере приложение открыть нельзя", html);
    }
}

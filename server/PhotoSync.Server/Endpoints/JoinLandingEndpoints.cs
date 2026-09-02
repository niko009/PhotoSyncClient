using System.Net;
using System.Text.Json;

namespace PhotoSync.Server.Endpoints;

public static class JoinLandingEndpoints
{
    private const string AndroidPackageName = "com.photosync.android";
    private const string AndroidCertificateSha256 = "0D:35:39:A6:6B:59:38:B4:CA:BB:D7:D7:6A:CE:E2:86:87:7A:5E:46:64:8B:4B:8C:6D:42:36:F5:C8:B9:86:2D";
    private const string AndroidDownloadUrl = "https://bacus.dev/downloads/photosync/photosync-android-0.5.0-beta.apk?v=b643cfaa2507-r1";

    public static IEndpointRouteBuilder MapJoinLanding(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGet("/.well-known/assetlinks.json", () => Results.Json(new[]
        {
            new
            {
                relation = new[] { "delegate_permission/common.handle_all_urls" },
                target = new Dictionary<string, object>
                {
                    ["namespace"] = "android_app",
                    ["package_name"] = AndroidPackageName,
                    ["sha256_cert_fingerprints"] = new[] { AndroidCertificateSha256 }
                }
            }
        }, new JsonSerializerOptions(JsonSerializerDefaults.Web)))
            .AllowAnonymous();

        endpoints.MapGet("/join/{token}", (string token) =>
        {
            if (token.Length is < 20 or > 256 || token.Any(character =>
                    !char.IsAsciiLetterOrDigit(character) && character is not '_' and not '-'))
                return Results.NotFound();

            var encodedToken = WebUtility.HtmlEncode(token);
            var encodedFallbackUrl = Uri.EscapeDataString(AndroidDownloadUrl);
            var html = $$"""
                <!doctype html>
                <html lang="ru">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <meta name="robots" content="noindex,nofollow">
                  <title>Приглашение в PhotoSync</title>
                </head>
                <body><main>
                  <h1>Приглашение в семейный альбом PhotoSync</h1>
                  <p>Откройте приглашение на Android-телефоне и войдите в тот Google-аккаунт, для которого оно создано.</p>
                  <p><a href="intent://join/{{encodedToken}}#Intent;scheme=photosync;package={{AndroidPackageName}};S.browser_fallback_url={{encodedFallbackUrl}};end">Открыть PhotoSync</a></p>
                  <p><a href="{{AndroidDownloadUrl}}">Скачать PhotoSync для Android</a></p>
                  <p>На компьютере приложение открыть нельзя. Скачайте APK на Android, установите его и снова перейдите по исходной ссылке приглашения.</p>
                  <p>В целях конфиденциальности сведения о семье на этой странице не отображаются.</p>
                </main></body></html>
                """;
            return Results.Content(html, "text/html; charset=utf-8");
        }).AllowAnonymous();
        return endpoints;
    }
}

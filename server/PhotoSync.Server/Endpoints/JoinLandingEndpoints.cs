using System.Net;

namespace PhotoSync.Server.Endpoints;

public static class JoinLandingEndpoints
{
    public static IEndpointRouteBuilder MapJoinLanding(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGet("/join/{token}", (string token) =>
        {
            if (string.IsNullOrWhiteSpace(token) || token.Length > 256)
                return Results.NotFound();

            var encodedToken = WebUtility.HtmlEncode(token);
            var html = $$"""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <meta name="robots" content="noindex,nofollow">
                  <title>Join PhotoSync family</title>
                  <style>
                    body{font-family:system-ui,sans-serif;max-width:34rem;margin:4rem auto;padding:0 1.25rem;line-height:1.5;background:#faf8f4;color:#25231f}
                    .card{background:white;border:1px solid #e8e2d8;border-radius:20px;padding:2rem;box-shadow:0 8px 30px #0000000d}
                    a{display:inline-block;margin-top:1rem;padding:.8rem 1.1rem;border-radius:12px;background:#25231f;color:white;text-decoration:none}
                    small{display:block;margin-top:1.25rem;color:#6c675f}
                  </style>
                </head>
                <body><main class="card">
                  <h1>PhotoSync family invitation</h1>
                  <p>Open this invitation in the PhotoSync Android app, then sign in with the Google account the invitation was created for.</p>
                  <a href="photosync://join/{{encodedToken}}">Open PhotoSync</a>
                  <small>If PhotoSync is not installed yet, install it first and open this invitation link again. This page intentionally shows no family details.</small>
                </main></body></html>
                """;
            return Results.Content(html, "text/html; charset=utf-8");
        }).AllowAnonymous();
        return endpoints;
    }
}

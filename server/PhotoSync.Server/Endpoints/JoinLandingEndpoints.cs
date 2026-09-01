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
                </head>
                <body><main>
                  <h1>PhotoSync family invitation</h1>
                  <p>Open this invitation in the PhotoSync Android app, then sign in with the Google account the invitation was created for.</p>
                  <p><a href="photosync://join/{{encodedToken}}">Open PhotoSync</a></p>
                  <p>If PhotoSync is not installed yet, install it first and open this invitation link again.</p>
                  <p>This page intentionally shows no family details.</p>
                </main></body></html>
                """;
            return Results.Content(html, "text/html; charset=utf-8");
        }).AllowAnonymous();
        return endpoints;
    }
}

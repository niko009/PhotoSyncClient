using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.Extensions.Options;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Options;
using PhotoSync.Server.Services;

namespace PhotoSync.Server.Endpoints;

public static class ServerEndpoints
{
    public static RouteGroupBuilder MapServerEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/server");

        group.MapGet("/info", (IOptions<PhotoSyncOptions> options, StoragePathResolver pathResolver) =>
        {
            var version = typeof(Program).Assembly.GetName().Version?.ToString() ?? "0.1.0";
            var response = new ServerInfoResponse(
                options.Value.ServerName,
                version,
                "",
                "ok",
                new ServerFeatures(false, false, false));

            return TypedResults.Ok(response);
        }).AllowAnonymous();

        group.MapGet("/capabilities", () => TypedResults.Ok(new
        {
            device_auth = true,
            google_auth = false,
            family_sharing = false,
            protocol_version = 2
        })).AllowAnonymous();

        return group;
    }
}

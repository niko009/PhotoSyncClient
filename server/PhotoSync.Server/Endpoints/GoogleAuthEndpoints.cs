using System.Security.Claims;
using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Security;

namespace PhotoSync.Server.Endpoints;

public static class GoogleAuthEndpoints
{
    public static RouteGroupBuilder MapGoogleAuthEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/auth/google");
        group.MapPost("/sign-in", SignInAsync).RequireRateLimiting("portal-login");
        group.MapGet("/me", MeAsync);
        group.MapPost("/sign-out", SignOutAsync);
        return group;
    }

    private static async Task<IResult> SignInAsync(GoogleSignInRequest request, HttpContext context,
        IGoogleTokenVerifier verifier, PhotoSyncDbContext db, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(request.IdToken) || request.IdToken.Length > 16_384)
            return Results.BadRequest(new { error = "invalid_google_token" });
        var identity = await verifier.VerifyAsync(request.IdToken, cancellationToken);
        if (identity is null) return Results.Unauthorized();
        var device = await CurrentDeviceAsync(context, db, cancellationToken);
        if (device is null) return Results.Unauthorized();
        device.GoogleSubject = identity.Subject;
        device.GoogleEmail = identity.Email;
        device.GoogleDisplayName = identity.DisplayName;
        await db.SaveChangesAsync(cancellationToken);
        return Results.Ok(await ResponseAsync(db, identity.Subject, identity.Email, identity.DisplayName, cancellationToken));
    }

    private static async Task<IResult> MeAsync(HttpContext context, PhotoSyncDbContext db, CancellationToken cancellationToken)
    {
        var device = await CurrentDeviceAsync(context, db, cancellationToken);
        if (device is null || string.IsNullOrWhiteSpace(device.GoogleSubject)) return Results.NoContent();
        return Results.Ok(await ResponseAsync(db, device.GoogleSubject, device.GoogleEmail!, device.GoogleDisplayName!, cancellationToken));
    }

    private static async Task<IResult> SignOutAsync(HttpContext context, PhotoSyncDbContext db, CancellationToken cancellationToken)
    {
        var device = await CurrentDeviceAsync(context, db, cancellationToken);
        if (device is null) return Results.Unauthorized();
        device.GoogleSubject = device.GoogleEmail = device.GoogleDisplayName = null;
        await db.SaveChangesAsync(cancellationToken);
        return Results.Ok(new { signed_out = true });
    }

    private static async Task<Models.DeviceEntity?> CurrentDeviceAsync(HttpContext context, PhotoSyncDbContext db, CancellationToken cancellationToken)
    {
        if (!int.TryParse(context.User.FindFirstValue(DeviceAuthentication.DeviceClaim), out var id)) return null;
        return await db.Devices.IgnoreQueryFilters().SingleOrDefaultAsync(x => x.Id == id, cancellationToken);
    }

    private static async Task<GoogleAccountResponse> ResponseAsync(PhotoSyncDbContext db, string subject, string email, string name, CancellationToken cancellationToken)
        => new(email, name, await db.Devices.IgnoreQueryFilters().CountAsync(x => x.GoogleSubject == subject, cancellationToken));
}

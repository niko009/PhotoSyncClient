using System.Security.Claims;
using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Models;
using PhotoSync.Server.Security;
using PhotoSync.Server.Services;

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

        await using var transaction = await db.Database.BeginTransactionAsync(cancellationToken);
        var user = await db.Users.SingleOrDefaultAsync(x => x.GoogleSubject == identity.Subject, cancellationToken);
        if (user is null)
        {
            user = new UserEntity
            {
                GoogleSubject = identity.Subject,
                GoogleEmail = NormalizeEmail(identity.Email),
                GoogleDisplayName = identity.DisplayName,
                CreatedAtUtc = DateTimeOffset.UtcNow
            };
            db.Users.Add(user);
            await db.SaveChangesAsync(cancellationToken);

            var family = new FamilyEntity
            {
                Name = string.IsNullOrWhiteSpace(identity.DisplayName) ? "My family" : identity.DisplayName + " family",
                CreatedAtUtc = DateTimeOffset.UtcNow
            };
            db.Families.Add(family);
            await db.SaveChangesAsync(cancellationToken);
            db.FamilyMembers.Add(new FamilyMemberEntity
            {
                FamilyId = family.Id,
                UserId = user.Id,
                Role = FamilyRole.Owner,
                IsActive = true,
                JoinedAtUtc = DateTimeOffset.UtcNow
            });
        }
        else
        {
            user.GoogleEmail = NormalizeEmail(identity.Email);
            user.GoogleDisplayName = identity.DisplayName;
        }

        device.UserId = user.Id;
        device.GoogleSubject = identity.Subject; // compatibility for 0.3.x clients/query filters
        device.GoogleEmail = identity.Email;
        device.GoogleDisplayName = identity.DisplayName;
        device.StorageFolderName = StoragePathResolver.MakeDeviceOwnerFolderName(device.DeviceName, identity.DisplayName);

        var unownedAlbums = await db.Albums.IgnoreQueryFilters()
            .Where(x => x.DeviceId == device.Id && x.OwnerUserId == null)
            .ToListAsync(cancellationToken);
        foreach (var album in unownedAlbums)
            album.OwnerUserId = user.Id;

        var unattributedFiles = await db.Files.IgnoreQueryFilters()
            .Where(x => x.DeviceId == device.Id && x.UploaderUserId == null)
            .ToListAsync(cancellationToken);
        foreach (var file in unattributedFiles)
            file.UploaderUserId = user.Id;

        await db.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
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
        device.UserId = null;
        device.StorageFolderName = StoragePathResolver.MakeDeviceOwnerFolderName(device.DeviceName, null);
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

    private static string NormalizeEmail(string email) => email.Trim().ToLowerInvariant();
}

using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Models;
using PhotoSync.Server.Security;

namespace PhotoSync.Server.Endpoints;

public static class FamilyEndpoints
{
    private static readonly TimeSpan InviteLifetime = TimeSpan.FromDays(7);

    public static RouteGroupBuilder MapFamilyEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/family");
        group.MapGet("/", GetFamilyAsync);
        group.MapPost("/invites", CreateInviteAsync).RequireRateLimiting("family-invite");
        group.MapDelete("/invites/{inviteId:int}", RevokeInviteAsync);
        group.MapPost("/join/{token}", AcceptInviteAsync).RequireRateLimiting("family-invite");
        group.MapDelete("/members/{userId:int}", RemoveMemberAsync);
        return group;
    }

    private static async Task<IResult> GetFamilyAsync(HttpContext context, PhotoSyncDbContext db, CancellationToken ct)
    {
        var current = await CurrentMembershipAsync(context, db, ct);
        if (current is null) return Results.Unauthorized();

        var members = await db.FamilyMembers.AsNoTracking()
            .Where(x => x.FamilyId == current.FamilyId && x.IsActive)
            .Include(x => x.User)
            .OrderByDescending(x => x.Role)
            .ThenBy(x => x.User.GoogleDisplayName)
            .Select(x => new FamilyMemberResponse(x.UserId, x.User.GoogleEmail, x.User.GoogleDisplayName,
                x.Role.ToString(), x.UserId == current.UserId))
            .ToListAsync(ct);

        var invites = current.Role == FamilyRole.Owner
            ? await db.FamilyInvitations.AsNoTracking()
                .Where(x => x.FamilyId == current.FamilyId && x.AcceptedAtUtc == null && x.RevokedAtUtc == null && x.ExpiresAtUtc > DateTimeOffset.UtcNow)
                .OrderByDescending(x => x.CreatedAtUtc)
                .Select(x => new FamilyInviteResponse(x.Id, x.ExpectedEmail, x.ExpiresAtUtc, "Pending", null))
                .ToListAsync(ct)
            : [];

        var family = await db.Families.AsNoTracking().SingleAsync(x => x.Id == current.FamilyId, ct);
        return Results.Ok(new FamilyResponse(family.Id, family.Name, current.Role.ToString(), members, invites));
    }

    private static async Task<IResult> CreateInviteAsync(CreateFamilyInviteRequest request, HttpContext context,
        PhotoSyncDbContext db, IConfiguration configuration, CancellationToken ct)
    {
        var current = await CurrentMembershipAsync(context, db, ct);
        if (current is null) return Results.Unauthorized();
        if (current.Role != FamilyRole.Owner) return Results.Forbid();

        var email = NormalizeEmail(request.Email);
        if (!IsValidEmail(email)) return Results.BadRequest(new { error = "invalid_email" });
        if (email == current.User.GoogleEmail) return Results.BadRequest(new { error = "cannot_invite_self" });

        var alreadyMember = await db.FamilyMembers.AsNoTracking()
            .AnyAsync(x => x.FamilyId == current.FamilyId && x.IsActive && x.User.GoogleEmail == email, ct);
        if (alreadyMember) return Results.Conflict(new { error = "already_member" });

        var rawToken = Base64Url(RandomNumberGenerator.GetBytes(32));
        var tokenHash = HashToken(rawToken);
        var now = DateTimeOffset.UtcNow;
        var invite = new FamilyInvitationEntity
        {
            FamilyId = current.FamilyId,
            InvitedByUserId = current.UserId,
            ExpectedEmail = email,
            TokenHash = tokenHash,
            CreatedAtUtc = now,
            ExpiresAtUtc = now + InviteLifetime
        };
        db.FamilyInvitations.Add(invite);
        await db.SaveChangesAsync(ct);

        var origin = configuration["Portal:PublicOrigin"]?.TrimEnd('/');
        if (string.IsNullOrWhiteSpace(origin)) origin = $"{context.Request.Scheme}://{context.Request.Host}";
        var url = $"{origin}/join/{rawToken}";
        return Results.Ok(new FamilyInviteResponse(invite.Id, invite.ExpectedEmail, invite.ExpiresAtUtc, "Pending", url));
    }

    private static async Task<IResult> RevokeInviteAsync(int inviteId, HttpContext context, PhotoSyncDbContext db, CancellationToken ct)
    {
        var current = await CurrentMembershipAsync(context, db, ct);
        if (current is null) return Results.Unauthorized();
        if (current.Role != FamilyRole.Owner) return Results.Forbid();
        var invite = await db.FamilyInvitations.SingleOrDefaultAsync(x => x.Id == inviteId && x.FamilyId == current.FamilyId, ct);
        if (invite is null) return Results.NotFound();
        if (invite.AcceptedAtUtc is not null) return Results.Conflict(new { error = "invite_already_used" });
        invite.RevokedAtUtc ??= DateTimeOffset.UtcNow;
        await db.SaveChangesAsync(ct);
        return Results.NoContent();
    }

    private static async Task<IResult> AcceptInviteAsync(string token, AcceptFamilyInviteRequest request, HttpContext context,
        PhotoSyncDbContext db, IGoogleTokenVerifier verifier, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(token) || token.Length > 256 || string.IsNullOrWhiteSpace(request.IdToken) || request.IdToken.Length > 16_384)
            return Results.BadRequest(new { error = "invalid_invite" });

        var currentUserId = CurrentUserId(context);
        if (currentUserId is null) return Results.Unauthorized();
        var identity = await verifier.VerifyAsync(request.IdToken, ct);
        if (identity is null) return Results.Unauthorized();

        var user = await db.Users.SingleOrDefaultAsync(x => x.Id == currentUserId.Value, ct);
        if (user is null || !string.Equals(user.GoogleSubject, identity.Subject, StringComparison.Ordinal)) return Results.Unauthorized();

        var hash = HashToken(token);
        await using var transaction = await db.Database.BeginTransactionAsync(System.Data.IsolationLevel.Serializable, ct);
        var invite = await db.FamilyInvitations.SingleOrDefaultAsync(x => x.TokenHash == hash, ct);
        if (invite is null) return Results.NotFound();
        var now = DateTimeOffset.UtcNow;
        if (invite.RevokedAtUtc is not null) return Results.Conflict(new { error = "invite_revoked" });
        if (invite.AcceptedAtUtc is not null) return Results.Conflict(new { error = "invite_already_used" });
        if (invite.ExpiresAtUtc <= now) return Results.Conflict(new { error = "invite_expired" });

        var verifiedEmail = NormalizeEmail(identity.Email);
        if (!string.Equals(verifiedEmail, invite.ExpectedEmail, StringComparison.Ordinal))
            return Results.BadRequest(new { error = "wrong_google_account", expected_email = MaskEmail(invite.ExpectedEmail) });

        var membership = await db.FamilyMembers.SingleOrDefaultAsync(x => x.UserId == user.Id, ct);
        if (membership is not null && membership.FamilyId != invite.FamilyId)
        {
            var oldFamilyMemberCount = await db.FamilyMembers.CountAsync(x => x.FamilyId == membership.FamilyId && x.IsActive, ct);
            if (membership.Role != FamilyRole.Owner || oldFamilyMemberCount != 1)
                return Results.Conflict(new { error = "already_in_another_family" });
            membership.FamilyId = invite.FamilyId;
            membership.Role = FamilyRole.Member;
            membership.IsActive = true;
            membership.RemovedAtUtc = null;
            membership.JoinedAtUtc = now;
        }
        else if (membership is null)
        {
            db.FamilyMembers.Add(new FamilyMemberEntity
            {
                FamilyId = invite.FamilyId,
                UserId = user.Id,
                Role = FamilyRole.Member,
                IsActive = true,
                JoinedAtUtc = now
            });
        }
        else
        {
            membership.IsActive = true;
            membership.RemovedAtUtc = null;
        }

        user.GoogleEmail = verifiedEmail;
        user.GoogleDisplayName = identity.DisplayName;
        invite.AcceptedAtUtc = now;
        invite.AcceptedByUserId = user.Id;
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return Results.Ok(new { joined = true, family_id = invite.FamilyId });
    }

    private static async Task<IResult> RemoveMemberAsync(int userId, HttpContext context, PhotoSyncDbContext db, CancellationToken ct)
    {
        var current = await CurrentMembershipAsync(context, db, ct);
        if (current is null) return Results.Unauthorized();
        if (current.Role != FamilyRole.Owner) return Results.Forbid();
        if (userId == current.UserId) return Results.BadRequest(new { error = "owner_cannot_remove_self" });

        var member = await db.FamilyMembers.SingleOrDefaultAsync(x => x.FamilyId == current.FamilyId && x.UserId == userId && x.IsActive, ct);
        if (member is null) return Results.NotFound();
        member.IsActive = false;
        member.RemovedAtUtc = DateTimeOffset.UtcNow;
        await db.SaveChangesAsync(ct);
        return Results.NoContent();
    }

    private static async Task<FamilyMemberEntity?> CurrentMembershipAsync(HttpContext context, PhotoSyncDbContext db, CancellationToken ct)
    {
        var userId = CurrentUserId(context);
        if (userId is null) return null;
        return await db.FamilyMembers.Include(x => x.User)
            .SingleOrDefaultAsync(x => x.UserId == userId.Value && x.IsActive, ct);
    }

    private static int? CurrentUserId(HttpContext context) =>
        int.TryParse(context.User.FindFirstValue(DeviceAuthentication.UserClaim), out var id) ? id : null;

    internal static string NormalizeEmail(string email) => email.Trim().ToLowerInvariant();
    internal static string HashToken(string token) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));
    private static string Base64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    private static bool IsValidEmail(string email)
    {
        if (email.Length is < 3 or > 320 || email.Any(char.IsWhiteSpace)) return false;
        try { return new System.Net.Mail.MailAddress(email).Address == email; }
        catch { return false; }
    }
    private static string MaskEmail(string email)
    {
        var at = email.IndexOf('@');
        if (at <= 1) return "***" + email[at..];
        return email[0] + "***" + email[(at - 1)..];
    }
}

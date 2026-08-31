using System.Security.Claims;
using Microsoft.AspNetCore.Antiforgery;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using PhotoSync.Server.Data;
using PhotoSync.Server.Options;
using PhotoSync.Server.Services;

namespace PhotoSync.Server.Portal;

public static class PortalEndpoints
{
    public static void MapPortal(this WebApplication app)
    {
        app.MapGet("/", () => Results.Redirect("/portal/")).AllowAnonymous();
        app.MapGet("/portal/", (IWebHostEnvironment env) => Results.File(Path.Combine(env.WebRootPath, "portal", "index.html"), "text/html")).AllowAnonymous();
        app.MapGet("/api/portal/status", async (HttpContext context, PortalDbContext db) =>
            Results.Ok(new { loginAvailable = context.Request.IsHttps && await db.Users.AnyAsync() })).AllowAnonymous();
        app.MapGet("/api/portal/csrf", async (HttpContext context, IAntiforgery csrf) =>
        {
            if (!context.Request.IsHttps)
                return Results.Json(new { error = "portal_setup_required" }, statusCode: StatusCodes.Status503ServiceUnavailable);
            var session = await context.AuthenticateAsync(PortalSetup.Scheme);
            context.User = session.Principal ?? new ClaimsPrincipal(new ClaimsIdentity());
            return Results.Ok(new { token = csrf.GetAndStoreTokens(context).RequestToken });
        }).AllowAnonymous();

        var portal = app.MapGroup("/api/portal").RequireAuthorization(PortalSetup.UserPolicy);
        portal.AddEndpointFilter(async (context, next) =>
        {
            if (!HttpMethods.IsGet(context.HttpContext.Request.Method))
            {
                try { await context.HttpContext.RequestServices.GetRequiredService<IAntiforgery>().ValidateRequestAsync(context.HttpContext); }
                catch (AntiforgeryValidationException) { return Results.BadRequest(new { error = "csrf_invalid" }); }
            }
            return await next(context);
        });

        portal.MapPost("/login", async (LoginRequest request, UserManager<PortalUser> users, HttpContext context) =>
        {
            if (string.IsNullOrWhiteSpace(request.UserName) || request.UserName.Length > 100 || string.IsNullOrEmpty(request.Password) || request.Password.Length > 256) return Results.Unauthorized();
            var user = await users.FindByNameAsync(request.UserName);
            if (user is null || await users.IsLockedOutAsync(user)) return Results.Unauthorized();
            if (!await users.CheckPasswordAsync(user, request.Password))
            {
                await users.AccessFailedAsync(user);
                return Results.Unauthorized();
            }
            await users.ResetAccessFailedCountAsync(user);
            var claims = new List<Claim> { new(ClaimTypes.NameIdentifier, user.Id), new(ClaimTypes.Name, user.UserName!), new("security_stamp", user.SecurityStamp!) };
            claims.AddRange((await users.GetRolesAsync(user)).Select(role => new Claim(ClaimTypes.Role, role)));
            await context.SignInAsync(PortalSetup.Scheme, new ClaimsPrincipal(new ClaimsIdentity(claims, PortalSetup.Scheme)));
            return Results.Ok(new { ok = true });
        }).AllowAnonymous().RequireRateLimiting("portal-login");

        portal.MapPost("/logout", async (HttpContext context) =>
        {
            await context.SignOutAsync(PortalSetup.Scheme);
            return Results.Ok(new { ok = true });
        });
        portal.MapGet("/me", (HttpContext context) => Results.Ok(new
        {
            name = context.User.Identity!.Name,
            roles = context.User.FindAll(ClaimTypes.Role).Select(x => x.Value)
        }));
        portal.MapPost("/password", async (PasswordRequest request, HttpContext context, UserManager<PortalUser> users) =>
        {
            if (string.IsNullOrEmpty(request.NewPassword) || request.NewPassword.Length > 256 || string.IsNullOrEmpty(request.CurrentPassword)) return Results.BadRequest(new { error = "invalid_password" });
            var user = (await users.GetUserAsync(context.User))!;
            var result = await users.ChangePasswordAsync(user, request.CurrentPassword, request.NewPassword);
            if (!result.Succeeded) return Results.BadRequest(new { errors = result.Errors.Select(x => x.Code) });
            await context.SignOutAsync(PortalSetup.Scheme);
            return Results.Ok(new { ok = true });
        });
        portal.MapGet("/dashboard", DashboardAsync);
        portal.MapGet("/files/{id:int}/download", async (int id, HttpContext context, PortalDbContext portalDb, PhotoSyncDbContext media, StoragePathResolver paths) =>
        {
            // Even administrators need ownership to download personal originals.
            var userId = context.User.FindFirstValue(ClaimTypes.NameIdentifier)!;
            var ids = await portalDb.DeviceOwners.Where(x => x.UserId == userId).Select(x => x.DeviceId).ToListAsync();
            var file = await media.Files.IgnoreQueryFilters().AsNoTracking().SingleOrDefaultAsync(x => x.Id == id && ids.Contains(x.DeviceId));
            if (file is null) return Results.NotFound();
            var path = paths.ToAbsolutePath(file.RelativePath);
            return File.Exists(path) ? Results.File(path, "application/octet-stream", file.OriginalName) : Results.NotFound();
        });

        var admin = portal.MapGroup("/admin").RequireAuthorization(PortalSetup.AdminPolicy);
        admin.MapGet("/dashboard", AdminDashboardAsync);
        admin.MapGet("/audit", async (PortalDbContext db) => Results.Ok(await db.Audit.AsNoTracking().OrderByDescending(x => x.Id).Take(100).ToListAsync()));
        var owner = admin.MapGroup("/users").RequireAuthorization(PortalSetup.OwnerPolicy);
        owner.MapGet("", async (UserManager<PortalUser> users) =>
        {
            var entries = await users.Users.AsNoTracking().OrderBy(x => x.UserName).Take(200).ToListAsync();
            var rows = new List<object>();
            foreach (var entry in entries) rows.Add(new { id = entry.Id, name = entry.UserName, roles = await users.GetRolesAsync(entry) });
            return Results.Ok(rows);
        });
        owner.MapPost("", async (CreateUserRequest request, UserManager<PortalUser> users, PortalDbContext db, HttpContext context) =>
        {
            if (string.IsNullOrWhiteSpace(request.UserName) || request.UserName.Length is < 3 or > 100 || string.IsNullOrEmpty(request.Password) || request.Password.Length > 256 || request.Role is not ("User" or "ServerAdmin"))
                return Results.BadRequest(new { error = "invalid_user" });
            var user = new PortalUser { UserName = request.UserName.Trim() };
            var result = await users.CreateAsync(user, request.Password);
            if (!result.Succeeded) return Results.BadRequest(new { errors = result.Errors.Select(x => x.Code) });
            var assigned = await users.AddToRoleAsync(user, request.Role);
            if (!assigned.Succeeded) { await users.DeleteAsync(user); return Results.Problem("Could not assign role."); }
            await Audit(db, context, "user_created", user.Id);
            return Results.Ok(new { id = user.Id, name = user.UserName });
        });
        admin.MapPost("/devices/{id:int}/owner", async (int id, AssignDeviceRequest request, PhotoSyncDbContext media, PortalDbContext db, UserManager<PortalUser> users, HttpContext context) =>
        {
            if (!await media.Devices.IgnoreQueryFilters().AnyAsync(x => x.Id == id) || await users.FindByIdAsync(request.UserId) is null)
                return Results.NotFound();
            if (await db.DeviceOwners.AnyAsync(x => x.DeviceId == id)) return Results.Conflict(new { error = "device_already_assigned" });
            db.DeviceOwners.Add(new DeviceOwnership { DeviceId = id, UserId = request.UserId });
            await Audit(db, context, "device_assigned", $"{id}:{request.UserId}");
            return Results.Ok(new { ok = true });
        }).RequireAuthorization(PortalSetup.OwnerPolicy);
    }

    private static async Task<IResult> DashboardAsync(HttpContext context, PortalDbContext db, PhotoSyncDbContext media)
    {
        var userId = context.User.FindFirstValue(ClaimTypes.NameIdentifier)!;
        var ids = await db.DeviceOwners.Where(x => x.UserId == userId).Select(x => x.DeviceId).ToListAsync();
        var devices = await media.Devices.IgnoreQueryFilters().AsNoTracking().Where(x => ids.Contains(x.Id))
            .Select(x => new { id = x.Id, name = x.DeviceName, lastSeenAt = x.LastSeenAtUtc, appVersion = x.AppVersion }).ToListAsync();
        var albums = await media.Albums.IgnoreQueryFilters().AsNoTracking().Where(x => ids.Contains(x.DeviceId))
            .OrderBy(x => x.Id).Take(200).Select(x => new { id = x.Id, deviceId = x.DeviceId, name = x.AlbumName }).ToListAsync();
        var query = media.Files.IgnoreQueryFilters().AsNoTracking().Where(x => ids.Contains(x.DeviceId));
        var count = await query.CountAsync();
        var bytes = await query.SumAsync(x => (long?)x.SizeBytes) ?? 0;
        var files = await query.OrderByDescending(x => x.Id).Take(100)
            .Select(x => new { id = x.Id, deviceId = x.DeviceId, albumId = x.AlbumId, name = x.OriginalName, bytes = x.SizeBytes, uploadedAt = x.UploadedAtUtc }).ToListAsync();
        return Results.Ok(new { devices, albums, files, fileCount = count, bytesTotal = bytes });
    }

    public static async Task<IResult> AdminDashboardAsync(PortalDbContext portal, PhotoSyncDbContext media, IOptions<PhotoSyncOptions> options, StoragePathResolver paths)
    {
        var devices = await media.Devices.IgnoreQueryFilters().AsNoTracking().OrderBy(x => x.Id).Take(500)
            .Select(x => new { id = x.Id, uuid = x.DeviceUuid, name = x.DeviceName, lastSeenAt = x.LastSeenAtUtc, appVersion = x.AppVersion }).ToListAsync();
        var owners = await portal.DeviceOwners.AsNoTracking().ToDictionaryAsync(x => x.DeviceId, x => x.UserId);
        var counts = await media.Files.IgnoreQueryFilters().GroupBy(x => x.DeviceId)
            .Select(x => new { deviceId = x.Key, fileCount = x.Count(), bytes = x.Sum(f => f.SizeBytes) }).ToListAsync();
        var database = await media.Database.CanConnectAsync();
        var freeBytes = UploadGuard.FreeBytes(paths.StorageRoot);
        return Results.Ok(new
        {
            server = new { name = options.Value.ServerName, protocolVersion = 2, database, freeBytes, serverCount = 1, multiServerManagement = false,
                maxDevices = options.Value.MaxDevices, maxStorageBytes = options.Value.MaxStorageBytes, maxFileBytes = options.Value.MaxFileBytes, allowDeviceRegistration = options.Value.AllowDeviceRegistration },
            devices = devices.Select(x => new { x.id, x.uuid, x.name, x.lastSeenAt, x.appVersion, ownerId = owners.GetValueOrDefault(x.id),
                fileCount = counts.FirstOrDefault(c => c.deviceId == x.id)?.fileCount ?? 0,
                bytes = counts.FirstOrDefault(c => c.deviceId == x.id)?.bytes ?? 0 }),
            deviceCount = await media.Devices.IgnoreQueryFilters().CountAsync(),
            fileCount = counts.Sum(x => x.fileCount), bytesTotal = counts.Sum(x => x.bytes)
        });
    }

    private static async Task Audit(PortalDbContext db, HttpContext context, string action, string target)
    {
        db.Audit.Add(new PortalAudit { ActorId = context.User.FindFirstValue(ClaimTypes.NameIdentifier)!, Action = action, Target = target });
        await db.SaveChangesAsync();
    }

    public sealed record LoginRequest(string UserName, string Password);
    public sealed record PasswordRequest(string CurrentPassword, string NewPassword);
    public sealed record CreateUserRequest(string UserName, string Password, string Role);
    public sealed record AssignDeviceRequest(string UserId);
}

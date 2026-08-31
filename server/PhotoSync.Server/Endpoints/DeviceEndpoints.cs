using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Models;
using PhotoSync.Server.Services;
using PhotoSync.Server.Security;

namespace PhotoSync.Server.Endpoints;

public static class DeviceEndpoints
{
    public static RouteGroupBuilder MapDeviceEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/devices");

        group.MapPost("/register", RegisterAsync)
            .AllowAnonymous()
            .RequireRateLimiting("portal-login")
            .Produces<RegisterDeviceResponse>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status400BadRequest);

        group.MapGet(string.Empty, ListAsync)
            .Produces(StatusCodes.Status200OK);

        return group;
    }

    private static async Task<IResult> RegisterAsync(
        RegisterDeviceRequest request,
        HttpRequest httpRequest,
        PhotoSyncDbContext dbContext,
        Microsoft.Extensions.Options.IOptions<PhotoSync.Server.Options.PhotoSyncOptions> options,
        CancellationToken cancellationToken)
    {
        if (request.DeviceUuid == Guid.Empty || string.IsNullOrWhiteSpace(request.DeviceName) || request.DeviceName.Length > 200 || string.IsNullOrWhiteSpace(request.AppVersion) || request.AppVersion.Length > 50)
        {
            return TypedResults.BadRequest(ApiProblems.Validation("INVALID_DEVICE", "device_uuid, device_name and app_version are required."));
        }

        var secret = DeviceAuthentication.ReadSecret(httpRequest);
        if (secret is null || !Guid.TryParse(httpRequest.Headers["X-PhotoSync-Device"], out var headerUuid) || headerUuid != request.DeviceUuid)
            return Results.Unauthorized();

        var now = DateTimeOffset.UtcNow;
        await using var transaction = await dbContext.Database.BeginTransactionAsync(cancellationToken);
        var device = await dbContext.Devices.IgnoreQueryFilters().SingleOrDefaultAsync(x => x.DeviceUuid == request.DeviceUuid, cancellationToken);
        if (device is null)
        {
            if (!options.Value.AllowDeviceRegistration || await dbContext.Devices.IgnoreQueryFilters().CountAsync(cancellationToken) >= options.Value.MaxDevices)
                return Results.Problem(statusCode: 403, title: "DEVICE_ENROLLMENT_CLOSED", detail: "New device registration is closed or the device limit was reached.");
            device = new DeviceEntity
            {
                DeviceUuid = request.DeviceUuid,
                DeviceName = request.DeviceName.Trim(),
                AppVersion = request.AppVersion.Trim(),
                StorageFolderName = StoragePathResolver.MakeDeviceFolderName(request.DeviceName, request.DeviceUuid),
                LastSeenAtUtc = now
            };

            dbContext.Devices.Add(device);
            await dbContext.SaveChangesAsync(cancellationToken);
            dbContext.DeviceCredentials.Add(new DeviceCredential { DeviceId = device.Id, SecretHash = DeviceAuthentication.Hash(secret) });
        }
        else
        {
            var credential = await dbContext.DeviceCredentials.SingleOrDefaultAsync(x => x.DeviceId == device.Id, cancellationToken);
            // Never let the first caller claim an old, unprotected device UUID.
            if (credential is null || !DeviceAuthentication.Matches(secret, credential.SecretHash))
                return Results.Unauthorized();
            device.DeviceName = request.DeviceName.Trim();
            device.AppVersion = request.AppVersion.Trim();
            device.LastSeenAtUtc = now;
        }

        await dbContext.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return TypedResults.Ok(new RegisterDeviceResponse(device.Id, true, device.LastSeenAtUtc));
    }

    private static async Task<Ok<object>> ListAsync(
        PhotoSyncDbContext dbContext,
        CancellationToken cancellationToken)
    {
        var devices = await dbContext.Devices.AsNoTracking()
            .OrderBy(x => x.DeviceName)
            .Select(x => new
            {
                id = x.Id,
                device_uuid = x.DeviceUuid,
                device_name = x.DeviceName,
                last_seen_at = x.LastSeenAtUtc,
                files_uploaded = x.Albums.SelectMany(a => a.Files).Count(),
                bytes_uploaded = x.Albums.SelectMany(a => a.Files).Sum(f => (long?)f.SizeBytes) ?? 0
            })
            .ToListAsync(cancellationToken);

        return TypedResults.Ok<object>(new { devices });
    }
}

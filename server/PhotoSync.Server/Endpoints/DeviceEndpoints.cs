using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Models;
using PhotoSync.Server.Services;

namespace PhotoSync.Server.Endpoints;

public static class DeviceEndpoints
{
    public static RouteGroupBuilder MapDeviceEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/devices");

        group.MapPost("/register", RegisterAsync)
            .Produces<RegisterDeviceResponse>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status400BadRequest);

        group.MapGet(string.Empty, ListAsync)
            .Produces(StatusCodes.Status200OK);

        return group;
    }

    private static async Task<Results<Ok<RegisterDeviceResponse>, BadRequest<ProblemDetails>>> RegisterAsync(
        RegisterDeviceRequest request,
        PhotoSyncDbContext dbContext,
        CancellationToken cancellationToken)
    {
        if (request.DeviceUuid == Guid.Empty || string.IsNullOrWhiteSpace(request.DeviceName) || string.IsNullOrWhiteSpace(request.AppVersion))
        {
            return TypedResults.BadRequest(ApiProblems.Validation("INVALID_DEVICE", "device_uuid, device_name and app_version are required."));
        }

        var now = DateTimeOffset.UtcNow;
        var device = await dbContext.Devices.SingleOrDefaultAsync(x => x.DeviceUuid == request.DeviceUuid, cancellationToken);
        if (device is null)
        {
            device = new DeviceEntity
            {
                DeviceUuid = request.DeviceUuid,
                DeviceName = request.DeviceName.Trim(),
                AppVersion = request.AppVersion.Trim(),
                StorageFolderName = StoragePathResolver.MakeDeviceFolderName(request.DeviceName, request.DeviceUuid),
                LastSeenAtUtc = now
            };

            dbContext.Devices.Add(device);
        }
        else
        {
            device.DeviceName = request.DeviceName.Trim();
            device.AppVersion = request.AppVersion.Trim();
            device.LastSeenAtUtc = now;
        }

        await dbContext.SaveChangesAsync(cancellationToken);
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

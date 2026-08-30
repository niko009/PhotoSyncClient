using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Models;
using PhotoSync.Server.Services;

namespace PhotoSync.Server.Endpoints;

public static class AlbumEndpoints
{
    public static RouteGroupBuilder MapAlbumEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/albums");

        group.MapGet("/", ListAsync)
            .Produces<AlbumsResponse>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status404NotFound);

        group.MapPost("/", CreateAsync)
            .Produces<CreateAlbumResponse>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status400BadRequest)
            .ProducesProblem(StatusCodes.Status404NotFound);

        return group;
    }

    private static async Task<Results<Ok<AlbumsResponse>, NotFound<ProblemDetails>>> ListAsync(
        Guid device_uuid,
        PhotoSyncDbContext dbContext,
        StoragePathResolver pathResolver,
        CancellationToken cancellationToken)
    {
        var device = await dbContext.Devices.AsNoTracking().SingleOrDefaultAsync(x => x.DeviceUuid == device_uuid, cancellationToken);
        if (device is null)
        {
            return TypedResults.NotFound(ApiProblems.NotFound("DEVICE_NOT_FOUND", $"Device '{device_uuid}' is not registered."));
        }

        var albums = await dbContext.Albums.AsNoTracking()
            .Where(x => x.DeviceId == device.Id)
            .OrderBy(x => x.AlbumName)
            .Select(x => new AlbumListItem(
                x.Id,
                x.AlbumName,
                pathResolver.GetAlbumRelativeDirectory(device, x).Replace('\\', '/')))
            .ToListAsync(cancellationToken);

        return TypedResults.Ok(new AlbumsResponse(albums));
    }

    private static async Task<Results<Ok<CreateAlbumResponse>, BadRequest<ProblemDetails>, NotFound<ProblemDetails>>> CreateAsync(
        CreateAlbumRequest request,
        PhotoSyncDbContext dbContext,
        StoragePathResolver pathResolver,
        CancellationToken cancellationToken)
    {
        if (request.DeviceUuid == Guid.Empty || string.IsNullOrWhiteSpace(request.AlbumName))
        {
            return TypedResults.BadRequest(ApiProblems.Validation("INVALID_ALBUM", "device_uuid and album_name are required."));
        }

        var device = await dbContext.Devices.SingleOrDefaultAsync(x => x.DeviceUuid == request.DeviceUuid, cancellationToken);
        if (device is null)
        {
            return TypedResults.NotFound(ApiProblems.NotFound("DEVICE_NOT_FOUND", $"Device '{request.DeviceUuid}' is not registered."));
        }

        var normalizedAlbumName = request.AlbumName.Trim();
        var album = await dbContext.Albums.SingleOrDefaultAsync(
            x => x.DeviceId == device.Id && x.AlbumName == normalizedAlbumName,
            cancellationToken);

        var created = false;
        if (album is null)
        {
            album = new AlbumEntity
            {
                DeviceId = device.Id,
                AlbumName = normalizedAlbumName,
                StorageFolderName = StoragePathResolver.MakeSafeFolderName(normalizedAlbumName),
                CreatedAtUtc = DateTimeOffset.UtcNow
            };

            dbContext.Albums.Add(album);
            await dbContext.SaveChangesAsync(cancellationToken);
            created = true;
        }

        var relativePath = pathResolver.GetAlbumRelativeDirectory(device, album).Replace('\\', '/');
        Directory.CreateDirectory(pathResolver.ToAbsolutePath(relativePath));

        return TypedResults.Ok(new CreateAlbumResponse(album.Id, created, relativePath));
    }
}

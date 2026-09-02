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
        group.MapGet("/", ListAsync);
        group.MapGet("/accessible", ListAccessibleAsync);
        group.MapGet("/{albumId:int}/sharing", GetSharingAsync);
        group.MapPost("/", CreateAsync);
        group.MapPut("/{albumId:int}/sharing", UpdateSharingAsync);
        group.MapPost("/{albumId:int}/archive", ArchiveAsync);
        return group;
    }

    private static async Task<IResult> ListAsync(Guid device_uuid, PhotoSyncDbContext dbContext,
        StoragePathResolver pathResolver, FolderAccessService access, CancellationToken ct)
    {
        var device = await dbContext.Devices.IgnoreQueryFilters().AsNoTracking()
            .SingleOrDefaultAsync(x => x.DeviceUuid == device_uuid, ct);
        if (device is null) return Results.NotFound(ApiProblems.NotFound("DEVICE_NOT_FOUND", "Device was not found."));

        var ownsDevice = access.CurrentDeviceId == device.Id ||
            (access.CurrentUserId is int userId && device.UserId == userId);
        if (!ownsDevice)
            return Results.NotFound(ApiProblems.NotFound("DEVICE_NOT_FOUND", "Device was not found."));

        var candidates = await dbContext.Albums.IgnoreQueryFilters().AsNoTracking()
            .Where(x => x.DeviceId == device.Id && x.ArchivedAtUtc == null)
            .OrderBy(x => x.AlbumName)
            .ToListAsync(ct);

        var visible = new List<AlbumListItem>();
        foreach (var album in candidates)
        {
            if (!await access.CanViewAsync(album, ct)) continue;
            visible.Add(new AlbumListItem(album.Id, album.AlbumName,
                pathResolver.GetAlbumRelativeDirectory(device, album).Replace('\\', '/')));
        }
        return Results.Ok(new AlbumsResponse(visible));
    }

    private static async Task<IResult> ListAccessibleAsync(PhotoSyncDbContext db, FolderAccessService access, CancellationToken ct)
    {
        var candidates = await db.Albums.IgnoreQueryFilters().AsNoTracking()
            .Where(x => x.ArchivedAtUtc == null)
            .OrderBy(x => x.AlbumName)
            .ToListAsync(ct);
        var result = new List<AccessibleAlbumItem>();
        foreach (var album in candidates)
        {
            var permission = await access.GetPermissionAsync(album, ct);
            if (permission < FolderPermission.View) continue;
            result.Add(new AccessibleAlbumItem(
                album.Id,
                album.AlbumName,
                permission.ToString(),
                album.SharingMode.ToString(),
                permission == FolderPermission.Owner));
        }
        return Results.Ok(new AccessibleAlbumsResponse(result));
    }

    private static async Task<IResult> GetSharingAsync(int albumId, PhotoSyncDbContext db,
        FolderAccessService access, CancellationToken ct)
    {
        var album = await db.Albums.IgnoreQueryFilters().AsNoTracking()
            .SingleOrDefaultAsync(x => x.Id == albumId && x.ArchivedAtUtc == null, ct);
        if (album is null || !await access.CanManageAsync(album, ct)) return Results.NotFound();

        var selectedPeople = await db.FolderAcls.AsNoTracking()
            .Where(x => x.AlbumId == album.Id)
            .ToDictionaryAsync(x => x.UserId, x => x.Permission.ToString(), ct);

        return Results.Ok(new AlbumSharingResponse(
            album.Id,
            album.SharingMode.ToString(),
            album.FamilyPermission.ToString(),
            selectedPeople));
    }

    private static async Task<IResult> CreateAsync(CreateAlbumRequest request, PhotoSyncDbContext dbContext,
        StoragePathResolver pathResolver, FolderAccessService access, CancellationToken ct)
    {
        if (request.DeviceUuid == Guid.Empty || string.IsNullOrWhiteSpace(request.AlbumName))
            return Results.BadRequest(ApiProblems.Validation("INVALID_ALBUM", "device_uuid and album_name are required."));

        var device = await dbContext.Devices.IgnoreQueryFilters().SingleOrDefaultAsync(x => x.DeviceUuid == request.DeviceUuid, ct);
        if (device is null || access.CurrentDeviceId != device.Id)
            return Results.NotFound(ApiProblems.NotFound("DEVICE_NOT_FOUND", "Device was not found."));

        var name = request.AlbumName.Trim();
        var album = await dbContext.Albums.IgnoreQueryFilters()
            .SingleOrDefaultAsync(x => x.DeviceId == device.Id && x.AlbumName == name && x.ArchivedAtUtc == null, ct);

        if (album is not null && !await access.CanManageAsync(album, ct))
            return Results.NotFound(ApiProblems.NotFound("ALBUM_NOT_FOUND", "Album was not found."));

        var created = album is null;
        await using var transaction = created
            ? await dbContext.Database.BeginTransactionAsync(ct)
            : null;

        if (album is null)
        {
            album = new AlbumEntity
            {
                DeviceId = device.Id,
                OwnerUserId = access.CurrentUserId,
                AlbumName = name,
                StorageFolderName = StoragePathResolver.MakeSafeFolderName(name),
                SharingMode = FolderSharingMode.Private,
                FamilyPermission = FolderPermission.View,
                CreatedAtUtc = DateTimeOffset.UtcNow
            };
            dbContext.Albums.Add(album);
            await dbContext.SaveChangesAsync(ct);
        }

        var relativePath = pathResolver.GetAlbumRelativeDirectory(device, album).Replace('\\', '/');
        try
        {
            Directory.CreateDirectory(pathResolver.ToAbsolutePath(relativePath));
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            if (transaction is not null)
                await transaction.RollbackAsync(ct);

            return Results.Problem(
                statusCode: StatusCodes.Status503ServiceUnavailable,
                title: "PhotoSync storage is unavailable.",
                detail: "The album could not be created in the configured storage. Check the server storage mount and write permissions.",
                extensions: new Dictionary<string, object?> { ["error"] = "STORAGE_UNAVAILABLE" });
        }

        if (transaction is not null)
            await transaction.CommitAsync(ct);

        return Results.Ok(new CreateAlbumResponse(album.Id, created, relativePath));
    }

    private static async Task<IResult> UpdateSharingAsync(int albumId, UpdateAlbumSharingRequest request,
        PhotoSyncDbContext db, FolderAccessService access, CancellationToken ct)
    {
        var album = await db.Albums.IgnoreQueryFilters().SingleOrDefaultAsync(x => x.Id == albumId && x.ArchivedAtUtc == null, ct);
        if (album is null || !await access.CanManageAsync(album, ct)) return Results.NotFound();
        if (!Enum.TryParse<FolderSharingMode>(request.Mode, true, out var mode))
            return Results.BadRequest(new { error = "invalid_sharing_mode" });

        album.SharingMode = mode;
        if (mode == FolderSharingMode.WholeFamily)
        {
            if (!Enum.TryParse<FolderPermission>(request.FamilyPermission ?? "View", true, out var permission) ||
                permission is FolderPermission.None or FolderPermission.Owner)
                return Results.BadRequest(new { error = "invalid_family_permission" });
            album.FamilyPermission = permission;
        }

        var oldAcl = await db.FolderAcls.Where(x => x.AlbumId == album.Id).ToListAsync(ct);
        db.FolderAcls.RemoveRange(oldAcl);
        if (mode == FolderSharingMode.SelectedPeople && request.SelectedPeople is not null)
        {
            var ownerFamilyId = await db.FamilyMembers.AsNoTracking()
                .Where(x => x.UserId == album.OwnerUserId && x.IsActive).Select(x => x.FamilyId).SingleAsync(ct);
            foreach (var (userId, permissionText) in request.SelectedPeople)
            {
                if (userId == album.OwnerUserId) continue;
                if (!Enum.TryParse<FolderPermission>(permissionText, true, out var permission) ||
                    permission is FolderPermission.None or FolderPermission.Owner)
                    return Results.BadRequest(new { error = "invalid_selected_permission", user_id = userId });
                var activeFamilyMember = await db.FamilyMembers.AsNoTracking()
                    .AnyAsync(x => x.FamilyId == ownerFamilyId && x.UserId == userId && x.IsActive, ct);
                if (!activeFamilyMember) return Results.BadRequest(new { error = "selected_user_not_in_family", user_id = userId });
                db.FolderAcls.Add(new FolderAclEntity { AlbumId = album.Id, UserId = userId, Permission = permission });
            }
        }
        await db.SaveChangesAsync(ct);
        return Results.Ok(new { album_id = album.Id, mode = album.SharingMode.ToString(), family_permission = album.FamilyPermission.ToString() });
    }

    private static async Task<IResult> ArchiveAsync(int albumId, PhotoSyncDbContext db, FolderAccessService access, CancellationToken ct)
    {
        var album = await db.Albums.IgnoreQueryFilters().SingleOrDefaultAsync(x => x.Id == albumId && x.ArchivedAtUtc == null, ct);
        if (album is null || !await access.CanManageAsync(album, ct)) return Results.NotFound();

        album.ArchivedAtUtc = DateTimeOffset.UtcNow;
        await db.SaveChangesAsync(ct);
        return Results.Ok(new { archived = true, originals_preserved = true });
    }
}
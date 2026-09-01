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
        group.MapPost("/", CreateAsync);
        group.MapPut("/{albumId:int}/sharing", UpdateSharingAsync);
        return group;
    }

    private static async Task<IResult> ListAsync(Guid device_uuid, PhotoSyncDbContext dbContext,
        StoragePathResolver pathResolver, FolderAccessService access, CancellationToken ct)
    {
        var device = await dbContext.Devices.IgnoreQueryFilters().AsNoTracking()
            .SingleOrDefaultAsync(x => x.DeviceUuid == device_uuid, ct);
        if (device is null) return Results.NotFound(ApiProblems.NotFound("DEVICE_NOT_FOUND", "Device was not found."));

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
        var created = false;
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
            created = true;
        }
        else if (!await access.CanManageAsync(album, ct))
        {
            return Results.NotFound(ApiProblems.NotFound("ALBUM_NOT_FOUND", "Album was not found."));
        }

        var relativePath = pathResolver.GetAlbumRelativeDirectory(device, album).Replace('\\', '/');
        Directory.CreateDirectory(pathResolver.ToAbsolutePath(relativePath));
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
}

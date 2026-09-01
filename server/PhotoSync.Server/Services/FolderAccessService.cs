using System.Security.Claims;
using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Data;
using PhotoSync.Server.Models;
using PhotoSync.Server.Security;

namespace PhotoSync.Server.Services;

public sealed class FolderAccessService(PhotoSyncDbContext db, IHttpContextAccessor accessor)
{
    public int? CurrentUserId => int.TryParse(accessor.HttpContext?.User.FindFirstValue(DeviceAuthentication.UserClaim), out var id) ? id : null;
    public int? CurrentDeviceId => int.TryParse(accessor.HttpContext?.User.FindFirstValue(DeviceAuthentication.DeviceClaim), out var id) ? id : null;

    public async Task<FolderPermission> GetPermissionAsync(AlbumEntity album, CancellationToken ct)
    {
        var userId = CurrentUserId;
        if (userId is null)
            return CurrentDeviceId == album.DeviceId ? FolderPermission.Owner : FolderPermission.None;

        if (album.OwnerUserId == userId.Value) return FolderPermission.Owner;
        if (album.ArchivedAtUtc is not null) return FolderPermission.None;

        if (album.SharingMode == FolderSharingMode.SelectedPeople)
        {
            var acl = await db.FolderAcls.AsNoTracking()
                .SingleOrDefaultAsync(x => x.AlbumId == album.Id && x.UserId == userId.Value, ct);
            return acl?.Permission ?? FolderPermission.None;
        }

        if (album.SharingMode == FolderSharingMode.WholeFamily)
        {
            var ownerFamilyId = await db.FamilyMembers.AsNoTracking()
                .Where(x => x.UserId == album.OwnerUserId && x.IsActive)
                .Select(x => (int?)x.FamilyId)
                .SingleOrDefaultAsync(ct);
            if (ownerFamilyId is null) return FolderPermission.None;
            var activeMember = await db.FamilyMembers.AsNoTracking()
                .AnyAsync(x => x.UserId == userId.Value && x.FamilyId == ownerFamilyId.Value && x.IsActive, ct);
            return activeMember ? album.FamilyPermission : FolderPermission.None;
        }

        return FolderPermission.None;
    }

    public async Task<bool> CanViewAsync(AlbumEntity album, CancellationToken ct) =>
        await GetPermissionAsync(album, ct) >= FolderPermission.View;

    public async Task<bool> CanContributeAsync(AlbumEntity album, CancellationToken ct) =>
        await GetPermissionAsync(album, ct) >= FolderPermission.Contribute;

    public async Task<bool> CanManageAsync(AlbumEntity album, CancellationToken ct) =>
        await GetPermissionAsync(album, ct) == FolderPermission.Owner;
}

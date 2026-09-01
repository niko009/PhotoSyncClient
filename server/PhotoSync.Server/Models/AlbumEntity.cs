namespace PhotoSync.Server.Models;

public sealed class AlbumEntity
{
    public int Id { get; set; }

    public int DeviceId { get; set; }

    public DeviceEntity Device { get; set; } = null!;

    public int? OwnerUserId { get; set; }

    public UserEntity? OwnerUser { get; set; }

    public FolderSharingMode SharingMode { get; set; } = FolderSharingMode.Private;

    public FolderPermission FamilyPermission { get; set; } = FolderPermission.View;

    public DateTimeOffset? ArchivedAtUtc { get; set; }

    public string AlbumName { get; set; } = string.Empty;

    public string StorageFolderName { get; set; } = string.Empty;

    public DateTimeOffset CreatedAtUtc { get; set; }

    public ICollection<StoredFileEntity> Files { get; set; } = [];

    public ICollection<FolderAclEntity> AclEntries { get; set; } = [];
}

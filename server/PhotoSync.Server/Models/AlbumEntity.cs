namespace PhotoSync.Server.Models;

public sealed class AlbumEntity
{
    public int Id { get; set; }

    public int DeviceId { get; set; }

    public DeviceEntity Device { get; set; } = null!;

    public string AlbumName { get; set; } = string.Empty;

    public string StorageFolderName { get; set; } = string.Empty;

    public DateTimeOffset CreatedAtUtc { get; set; }

    public ICollection<StoredFileEntity> Files { get; set; } = [];
}

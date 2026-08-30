namespace PhotoSync.Server.Models;

public sealed class DeviceEntity
{
    public int Id { get; set; }

    public Guid DeviceUuid { get; set; }

    public string DeviceName { get; set; } = string.Empty;

    public string AppVersion { get; set; } = string.Empty;

    public string StorageFolderName { get; set; } = string.Empty;

    public DateTimeOffset LastSeenAtUtc { get; set; }

    public ICollection<AlbumEntity> Albums { get; set; } = [];
}

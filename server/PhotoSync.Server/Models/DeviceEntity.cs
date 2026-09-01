namespace PhotoSync.Server.Models;

public sealed class DeviceEntity
{
    public int Id { get; set; }

    public Guid DeviceUuid { get; set; }

    public string DeviceName { get; set; } = string.Empty;

    public string AppVersion { get; set; } = string.Empty;

    public string StorageFolderName { get; set; } = string.Empty;

    public DateTimeOffset LastSeenAtUtc { get; set; }

    public string? GoogleSubject { get; set; }

    public string? GoogleEmail { get; set; }

    public string? GoogleDisplayName { get; set; }

    public ICollection<AlbumEntity> Albums { get; set; } = [];
}

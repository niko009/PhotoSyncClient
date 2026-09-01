namespace PhotoSync.Server.Models;

public sealed class DeviceEntity
{
    public int Id { get; set; }

    public Guid DeviceUuid { get; set; }

    public string DeviceName { get; set; } = string.Empty;

    public string AppVersion { get; set; } = string.Empty;

    public string StorageFolderName { get; set; } = string.Empty;

    public DateTimeOffset LastSeenAtUtc { get; set; }

    // Compatibility profile fields retained during the 0.3.x migration. Permanent
    // account identity is User.GoogleSubject; new authorization must not key on email.
    public string? GoogleSubject { get; set; }

    public string? GoogleEmail { get; set; }

    public string? GoogleDisplayName { get; set; }

    public int? UserId { get; set; }

    public UserEntity? User { get; set; }

    public ICollection<AlbumEntity> Albums { get; set; } = [];
}

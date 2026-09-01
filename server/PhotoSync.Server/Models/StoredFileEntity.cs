namespace PhotoSync.Server.Models;

public sealed class StoredFileEntity
{
    public int Id { get; set; }

    public int DeviceId { get; set; }

    public DeviceEntity Device { get; set; } = null!;

    public int AlbumId { get; set; }

    public AlbumEntity Album { get; set; } = null!;

    public int? UploaderUserId { get; set; }

    public UserEntity? UploaderUser { get; set; }

    public string OriginalName { get; set; } = string.Empty;

    public string StoredName { get; set; } = string.Empty;

    public string MimeType { get; set; } = string.Empty;

    public long SizeBytes { get; set; }

    public string Sha256 { get; set; } = string.Empty;

    public DateTimeOffset CreatedAtUtc { get; set; }

    public int? Width { get; set; }

    public int? Height { get; set; }

    public long? DurationMs { get; set; }

    public bool IsVideo { get; set; }

    public string RelativePath { get; set; } = string.Empty;

    public DateTimeOffset UploadedAtUtc { get; set; }

    // User-facing removal is logical only. A committed original referenced by
    // RelativePath is immutable and must remain on storage.
    public DateTimeOffset? ArchivedAtUtc { get; set; }
}

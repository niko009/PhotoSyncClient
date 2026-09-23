namespace PhotoSync.Server.Models;

/// <summary>Durable, append-only state for an interrupted client upload.</summary>
public sealed class UploadSessionEntity
{
    public Guid Id { get; set; }
    public int DeviceId { get; set; }
    public int AlbumId { get; set; }
    public string OriginalName { get; set; } = string.Empty;
    public string MimeType { get; set; } = string.Empty;
    public long SizeBytes { get; set; }
    public string Sha256 { get; set; } = string.Empty;
    public DateTimeOffset CreatedAtUtc { get; set; }
    public bool IsVideo { get; set; }
    public long ReceivedBytes { get; set; }
    public int? StoredFileId { get; set; }
    public DateTimeOffset StartedAtUtc { get; set; }
    public DateTimeOffset UpdatedAtUtc { get; set; }
}

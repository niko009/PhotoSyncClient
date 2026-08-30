using System.Text.Json.Serialization;

namespace PhotoSync.Server.Contracts;

public sealed record FileCheckRequest(
    [property: JsonPropertyName("device_uuid")] Guid DeviceUuid,
    [property: JsonPropertyName("album_name")] string AlbumName,
    [property: JsonPropertyName("original_name")] string OriginalName,
    [property: JsonPropertyName("size_bytes")] long SizeBytes,
    [property: JsonPropertyName("sha256")] string Sha256);

public sealed record FileCheckResponse(
    [property: JsonPropertyName("exists")] bool Exists,
    [property: JsonPropertyName("server_file_id")] int? ServerFileId = null,
    [property: JsonPropertyName("relative_path")] string? RelativePath = null);

public sealed record UploadFileResponse(
    [property: JsonPropertyName("server_file_id")] int ServerFileId,
    [property: JsonPropertyName("stored_name")] string StoredName,
    [property: JsonPropertyName("relative_path")] string RelativePath,
    [property: JsonPropertyName("has_preview")] bool HasPreview,
    [property: JsonPropertyName("uploaded_at")] DateTimeOffset UploadedAt);

public sealed record FileListResponse(
    [property: JsonPropertyName("files")] IReadOnlyList<FileListItem> Files);

public sealed record FileListItem(
    [property: JsonPropertyName("server_file_id")] int ServerFileId,
    [property: JsonPropertyName("album_name")] string AlbumName,
    [property: JsonPropertyName("original_name")] string OriginalName,
    [property: JsonPropertyName("relative_path")] string RelativePath,
    [property: JsonPropertyName("mime_type")] string MimeType,
    [property: JsonPropertyName("size_bytes")] long SizeBytes,
    [property: JsonPropertyName("uploaded_at")] DateTimeOffset UploadedAt,
    [property: JsonPropertyName("preview_url")] string PreviewUrl,
    [property: JsonPropertyName("download_url")] string DownloadUrl);

public sealed record ServerInfoResponse(
    [property: JsonPropertyName("server_name")] string ServerName,
    [property: JsonPropertyName("server_version")] string ServerVersion,
    [property: JsonPropertyName("storage_root")] string StorageRoot,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("features")] ServerFeatures Features);

public sealed record ServerFeatures(
    [property: JsonPropertyName("chunk_upload")] bool ChunkUpload,
    [property: JsonPropertyName("preview_generation")] bool PreviewGeneration,
    [property: JsonPropertyName("photo_compression_policy_push")] bool PhotoCompressionPolicyPush);

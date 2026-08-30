using System.Text.Json.Serialization;

namespace PhotoSync.Server.Contracts;

public sealed record AdminDashboardResponse(
    [property: JsonPropertyName("device_count")] int DeviceCount,
    [property: JsonPropertyName("album_count")] int AlbumCount,
    [property: JsonPropertyName("file_count")] int FileCount,
    [property: JsonPropertyName("total_bytes")] long TotalBytes,
    [property: JsonPropertyName("devices")] IReadOnlyList<AdminDeviceItem> Devices);

public sealed record AdminDeviceItem(
    [property: JsonPropertyName("id")] int Id,
    [property: JsonPropertyName("device_uuid")] Guid DeviceUuid,
    [property: JsonPropertyName("device_name")] string DeviceName,
    [property: JsonPropertyName("app_version")] string AppVersion,
    [property: JsonPropertyName("last_seen_at")] DateTimeOffset LastSeenAtUtc,
    [property: JsonPropertyName("album_count")] int AlbumCount,
    [property: JsonPropertyName("file_count")] int FileCount,
    [property: JsonPropertyName("bytes_uploaded")] long BytesUploaded,
    [property: JsonPropertyName("albums")] IReadOnlyList<AdminAlbumItem> Albums);

public sealed record AdminAlbumItem(
    [property: JsonPropertyName("id")] int Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("file_count")] int FileCount,
    [property: JsonPropertyName("bytes_uploaded")] long BytesUploaded,
    [property: JsonPropertyName("relative_path")] string RelativePath);

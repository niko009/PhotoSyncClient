using System.Text.Json.Serialization;

namespace PhotoSync.Server.Contracts;

public sealed record RegisterDeviceRequest(
    [property: JsonPropertyName("device_uuid")] Guid DeviceUuid,
    [property: JsonPropertyName("device_name")] string DeviceName,
    [property: JsonPropertyName("app_version")] string AppVersion);

public sealed record RegisterDeviceResponse(
    [property: JsonPropertyName("device_id")] int DeviceId,
    [property: JsonPropertyName("registered")] bool Registered,
    [property: JsonPropertyName("last_seen_at")] DateTimeOffset LastSeenAt);

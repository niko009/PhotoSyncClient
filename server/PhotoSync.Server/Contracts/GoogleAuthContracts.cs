using System.Text.Json.Serialization;

namespace PhotoSync.Server.Contracts;

public sealed record GoogleSignInRequest([property: JsonPropertyName("id_token")] string IdToken);

public sealed record GoogleAccountResponse(
    [property: JsonPropertyName("email")] string Email,
    [property: JsonPropertyName("display_name")] string DisplayName,
    [property: JsonPropertyName("linked_devices")] int LinkedDevices);

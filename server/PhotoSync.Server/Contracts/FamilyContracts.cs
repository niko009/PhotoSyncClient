using System.Text.Json.Serialization;

namespace PhotoSync.Server.Contracts;

public sealed record CreateFamilyInviteRequest(
    [property: JsonPropertyName("email")] string Email);

public sealed record AcceptFamilyInviteRequest(
    [property: JsonPropertyName("id_token")] string IdToken);

public sealed record FamilyMemberResponse(
    [property: JsonPropertyName("user_id")] int UserId,
    [property: JsonPropertyName("email")] string Email,
    [property: JsonPropertyName("display_name")] string? DisplayName,
    [property: JsonPropertyName("role")] string Role,
    [property: JsonPropertyName("is_current_user")] bool IsCurrentUser);

public sealed record FamilyInviteResponse(
    [property: JsonPropertyName("id")] int Id,
    [property: JsonPropertyName("expected_email")] string ExpectedEmail,
    [property: JsonPropertyName("expires_at")] DateTimeOffset ExpiresAtUtc,
    [property: JsonPropertyName("status")] string Status,
    [property: JsonPropertyName("invite_url")] string? InviteUrl = null);

public sealed record FamilyResponse(
    [property: JsonPropertyName("id")] int Id,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("role")] string Role,
    [property: JsonPropertyName("members")] IReadOnlyList<FamilyMemberResponse> Members,
    [property: JsonPropertyName("pending_invites")] IReadOnlyList<FamilyInviteResponse> PendingInvites);

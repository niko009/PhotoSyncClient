namespace PhotoSync.Server.Contracts;

public sealed record CreateFamilyInviteRequest(string Email);
public sealed record AcceptFamilyInviteRequest(string IdToken);
public sealed record FamilyMemberResponse(int UserId, string Email, string? DisplayName, string Role, bool IsCurrentUser);
public sealed record FamilyInviteResponse(int Id, string ExpectedEmail, DateTimeOffset ExpiresAtUtc, string Status, string? InviteUrl = null);
public sealed record FamilyResponse(int Id, string Name, string Role, IReadOnlyList<FamilyMemberResponse> Members, IReadOnlyList<FamilyInviteResponse> PendingInvites);

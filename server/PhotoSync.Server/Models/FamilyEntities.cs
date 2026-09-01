namespace PhotoSync.Server.Models;

public enum FamilyRole
{
    Member = 0,
    Owner = 1
}

public enum FolderSharingMode
{
    Private = 0,
    WholeFamily = 1,
    SelectedPeople = 2
}

public enum FolderPermission
{
    None = 0,
    View = 1,
    Contribute = 2,
    Owner = 3
}

public sealed class UserEntity
{
    public int Id { get; set; }
    public string GoogleSubject { get; set; } = string.Empty;
    public string GoogleEmail { get; set; } = string.Empty;
    public string? GoogleDisplayName { get; set; }
    public DateTimeOffset CreatedAtUtc { get; set; }
}

public sealed class FamilyEntity
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;
    public DateTimeOffset CreatedAtUtc { get; set; }
}

public sealed class FamilyMemberEntity
{
    public int Id { get; set; }
    public int FamilyId { get; set; }
    public FamilyEntity Family { get; set; } = null!;
    public int UserId { get; set; }
    public UserEntity User { get; set; } = null!;
    public FamilyRole Role { get; set; }
    public bool IsActive { get; set; } = true;
    public DateTimeOffset JoinedAtUtc { get; set; }
    public DateTimeOffset? RemovedAtUtc { get; set; }
}

public sealed class FamilyInvitationEntity
{
    public int Id { get; set; }
    public int FamilyId { get; set; }
    public FamilyEntity Family { get; set; } = null!;
    public int InvitedByUserId { get; set; }
    public UserEntity InvitedByUser { get; set; } = null!;
    public string ExpectedEmail { get; set; } = string.Empty;
    public string TokenHash { get; set; } = string.Empty;
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset ExpiresAtUtc { get; set; }
    public DateTimeOffset? AcceptedAtUtc { get; set; }
    public int? AcceptedByUserId { get; set; }
    public DateTimeOffset? RevokedAtUtc { get; set; }
}

public sealed class FolderAclEntity
{
    public int Id { get; set; }
    public int AlbumId { get; set; }
    public AlbumEntity Album { get; set; } = null!;
    public int UserId { get; set; }
    public UserEntity User { get; set; } = null!;
    public FolderPermission Permission { get; set; }
}

using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Models;
using PhotoSync.Server.Security;

namespace PhotoSync.Server.Data;

public sealed class PhotoSyncDbContext(DbContextOptions<PhotoSyncDbContext> options, IHttpContextAccessor? accessor = null) : DbContext(options)
{
    // Internal maintenance scopes have no HTTP context. Every HTTP request is scoped.
    private bool IsHttpRequest => accessor?.HttpContext is not null;
    private int CallerDeviceId => int.TryParse(accessor?.HttpContext?.User.FindFirst(DeviceAuthentication.DeviceClaim)?.Value, out var id) ? id : -1;
    private string? CallerGoogleSubject => accessor?.HttpContext?.User.FindFirst(DeviceAuthentication.GoogleSubjectClaim)?.Value;

    public DbSet<DeviceCredential> DeviceCredentials => Set<DeviceCredential>();
    public DbSet<DeviceEntity> Devices => Set<DeviceEntity>();
    public DbSet<AlbumEntity> Albums => Set<AlbumEntity>();
    public DbSet<StoredFileEntity> Files => Set<StoredFileEntity>();
    public DbSet<UserEntity> Users => Set<UserEntity>();
    public DbSet<FamilyEntity> Families => Set<FamilyEntity>();
    public DbSet<FamilyMemberEntity> FamilyMembers => Set<FamilyMemberEntity>();
    public DbSet<FamilyInvitationEntity> FamilyInvitations => Set<FamilyInvitationEntity>();
    public DbSet<FolderAclEntity> FolderAcls => Set<FolderAclEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<DeviceCredential>(entity =>
        {
            entity.ToTable("device_credentials");
            entity.HasKey(x => x.DeviceId);
            entity.Property(x => x.SecretHash).HasMaxLength(64).IsRequired();
            entity.HasOne<DeviceEntity>().WithOne().HasForeignKey<DeviceCredential>(x => x.DeviceId);
        });

        modelBuilder.Entity<UserEntity>(entity =>
        {
            entity.ToTable("users");
            entity.HasKey(x => x.Id);
            entity.Property(x => x.GoogleSubject).HasMaxLength(255).IsRequired();
            entity.Property(x => x.GoogleEmail).HasMaxLength(320).IsRequired();
            entity.Property(x => x.GoogleDisplayName).HasMaxLength(200);
            entity.HasIndex(x => x.GoogleSubject).IsUnique();
        });

        modelBuilder.Entity<FamilyEntity>(entity =>
        {
            entity.ToTable("families");
            entity.HasKey(x => x.Id);
            entity.Property(x => x.Name).HasMaxLength(200).IsRequired();
        });

        modelBuilder.Entity<FamilyMemberEntity>(entity =>
        {
            entity.ToTable("family_members");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => new { x.FamilyId, x.UserId }).IsUnique();
            entity.HasIndex(x => x.UserId).IsUnique(); // one family per user in this phase
            entity.HasOne(x => x.Family).WithMany().HasForeignKey(x => x.FamilyId).OnDelete(DeleteBehavior.Restrict);
            entity.HasOne(x => x.User).WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Restrict);
        });

        modelBuilder.Entity<FamilyInvitationEntity>(entity =>
        {
            entity.ToTable("family_invitations");
            entity.HasKey(x => x.Id);
            entity.Property(x => x.ExpectedEmail).HasMaxLength(320).IsRequired();
            entity.Property(x => x.TokenHash).HasMaxLength(64).IsRequired();
            entity.HasIndex(x => x.TokenHash).IsUnique();
            entity.HasOne(x => x.Family).WithMany().HasForeignKey(x => x.FamilyId).OnDelete(DeleteBehavior.Restrict);
            entity.HasOne(x => x.InvitedByUser).WithMany().HasForeignKey(x => x.InvitedByUserId).OnDelete(DeleteBehavior.Restrict);
        });

        modelBuilder.Entity<DeviceEntity>(entity =>
        {
            entity.HasQueryFilter(x => !IsHttpRequest || x.Id == CallerDeviceId ||
                (CallerGoogleSubject != null && x.GoogleSubject == CallerGoogleSubject));
            entity.ToTable("devices");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => x.DeviceUuid).IsUnique();
            entity.Property(x => x.DeviceName).HasMaxLength(200).IsRequired();
            entity.Property(x => x.AppVersion).HasMaxLength(50).IsRequired();
            entity.Property(x => x.StorageFolderName).HasMaxLength(250).IsRequired();
            entity.Property(x => x.GoogleSubject).HasMaxLength(255);
            entity.Property(x => x.GoogleEmail).HasMaxLength(320);
            entity.Property(x => x.GoogleDisplayName).HasMaxLength(200);
            entity.HasIndex(x => x.GoogleSubject);
            entity.HasOne(x => x.User).WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.SetNull);
        });

        modelBuilder.Entity<AlbumEntity>(entity =>
        {
            entity.HasQueryFilter(x => !IsHttpRequest || x.DeviceId == CallerDeviceId ||
                (CallerGoogleSubject != null && x.Device.GoogleSubject == CallerGoogleSubject));
            entity.ToTable("albums");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => new { x.DeviceId, x.AlbumName }).IsUnique();
            entity.Property(x => x.AlbumName).HasMaxLength(200).IsRequired();
            entity.Property(x => x.StorageFolderName).HasMaxLength(250).IsRequired();
            entity.HasOne(x => x.Device).WithMany(x => x.Albums).HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.Cascade);
            entity.HasOne(x => x.OwnerUser).WithMany().HasForeignKey(x => x.OwnerUserId).OnDelete(DeleteBehavior.Restrict);
        });

        modelBuilder.Entity<FolderAclEntity>(entity =>
        {
            entity.ToTable("folder_acl");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => new { x.AlbumId, x.UserId }).IsUnique();
            entity.HasOne(x => x.Album).WithMany(x => x.AclEntries).HasForeignKey(x => x.AlbumId).OnDelete(DeleteBehavior.Cascade);
            entity.HasOne(x => x.User).WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<StoredFileEntity>(entity =>
        {
            entity.HasQueryFilter(x => !IsHttpRequest || x.DeviceId == CallerDeviceId ||
                (CallerGoogleSubject != null && x.Device.GoogleSubject == CallerGoogleSubject));
            entity.ToTable("files");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => new { x.DeviceId, x.AlbumId, x.Sha256 }).IsUnique();
            entity.Property(x => x.OriginalName).HasMaxLength(260).IsRequired();
            entity.Property(x => x.StoredName).HasMaxLength(260).IsRequired();
            entity.Property(x => x.MimeType).HasMaxLength(100).IsRequired();
            entity.Property(x => x.Sha256).HasMaxLength(64).IsRequired();
            entity.Property(x => x.RelativePath).HasMaxLength(1024).IsRequired();
            entity.HasOne(x => x.Device).WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.Restrict);
            entity.HasOne(x => x.Album).WithMany(x => x.Files).HasForeignKey(x => x.AlbumId).OnDelete(DeleteBehavior.Restrict);
            entity.HasOne(x => x.UploaderUser).WithMany().HasForeignKey(x => x.UploaderUserId).OnDelete(DeleteBehavior.Restrict);
        });
    }
}

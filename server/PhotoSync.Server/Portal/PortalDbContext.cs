using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace PhotoSync.Server.Portal;

public sealed class PortalUser : IdentityUser;

public sealed class DeviceOwnership
{
    public int DeviceId { get; set; }
    public string UserId { get; set; } = "";
}

public sealed class PortalAudit
{
    public long Id { get; set; }
    public DateTimeOffset AtUtc { get; set; } = DateTimeOffset.UtcNow;
    public string ActorId { get; set; } = "";
    public string Action { get; set; } = "";
    public string Target { get; set; } = "";
}

// Separate from the legacy EnsureCreated media database; no destructive migration.
public sealed class PortalDbContext(DbContextOptions<PortalDbContext> options)
    : IdentityDbContext<PortalUser>(options)
{
    public DbSet<DeviceOwnership> DeviceOwners => Set<DeviceOwnership>();
    public DbSet<PortalAudit> Audit => Set<PortalAudit>();

    protected override void OnModelCreating(ModelBuilder builder)
    {
        base.OnModelCreating(builder);
        builder.Entity<DeviceOwnership>().HasKey(x => x.DeviceId);
        builder.Entity<DeviceOwnership>().HasOne<PortalUser>().WithMany().HasForeignKey(x => x.UserId);
        builder.Entity<PortalAudit>().HasKey(x => x.Id);
    }
}

using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Models;

namespace PhotoSync.Server.Data;

public sealed class PhotoSyncDbContext(DbContextOptions<PhotoSyncDbContext> options) : DbContext(options)
{
    public DbSet<DeviceEntity> Devices => Set<DeviceEntity>();

    public DbSet<AlbumEntity> Albums => Set<AlbumEntity>();

    public DbSet<StoredFileEntity> Files => Set<StoredFileEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<DeviceEntity>(entity =>
        {
            entity.ToTable("devices");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => x.DeviceUuid).IsUnique();
            entity.Property(x => x.DeviceName).HasMaxLength(200).IsRequired();
            entity.Property(x => x.AppVersion).HasMaxLength(50).IsRequired();
            entity.Property(x => x.StorageFolderName).HasMaxLength(250).IsRequired();
        });

        modelBuilder.Entity<AlbumEntity>(entity =>
        {
            entity.ToTable("albums");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => new { x.DeviceId, x.AlbumName }).IsUnique();
            entity.Property(x => x.AlbumName).HasMaxLength(200).IsRequired();
            entity.Property(x => x.StorageFolderName).HasMaxLength(250).IsRequired();
            entity.HasOne(x => x.Device)
                .WithMany(x => x.Albums)
                .HasForeignKey(x => x.DeviceId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<StoredFileEntity>(entity =>
        {
            entity.ToTable("files");
            entity.HasKey(x => x.Id);
            entity.HasIndex(x => x.Sha256).IsUnique();
            entity.Property(x => x.OriginalName).HasMaxLength(260).IsRequired();
            entity.Property(x => x.StoredName).HasMaxLength(260).IsRequired();
            entity.Property(x => x.MimeType).HasMaxLength(100).IsRequired();
            entity.Property(x => x.Sha256).HasMaxLength(64).IsRequired();
            entity.Property(x => x.RelativePath).HasMaxLength(1024).IsRequired();
            entity.HasOne(x => x.Device)
                .WithMany()
                .HasForeignKey(x => x.DeviceId)
                .OnDelete(DeleteBehavior.Restrict);
            entity.HasOne(x => x.Album)
                .WithMany(x => x.Files)
                .HasForeignKey(x => x.AlbumId)
                .OnDelete(DeleteBehavior.Cascade);
        });
    }
}

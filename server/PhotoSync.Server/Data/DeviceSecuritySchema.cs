using Microsoft.EntityFrameworkCore;

namespace PhotoSync.Server.Data;

/// <summary>Additive upgrade for databases originally created with EnsureCreated.</summary>
public static class DeviceSecuritySchema
{
    public static async Task InitializeAsync(PhotoSyncDbContext db)
    {
        await db.Database.EnsureCreatedAsync();
        await using var transaction = await db.Database.BeginTransactionAsync();
        await db.Database.ExecuteSqlRawAsync("""
            CREATE TABLE IF NOT EXISTS device_credentials (
                DeviceId INTEGER NOT NULL PRIMARY KEY,
                SecretHash TEXT NOT NULL,
                FOREIGN KEY (DeviceId) REFERENCES devices (Id) ON DELETE CASCADE
            );
            DROP INDEX IF EXISTS IX_files_Sha256;
            CREATE UNIQUE INDEX IF NOT EXISTS IX_files_DeviceId_AlbumId_Sha256
                ON files (DeviceId, AlbumId, Sha256);
            """);
        await transaction.CommitAsync();
    }
}

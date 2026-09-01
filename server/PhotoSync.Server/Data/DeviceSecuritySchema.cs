using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;

namespace PhotoSync.Server.Data;

/// <summary>Additive upgrade for databases originally created with EnsureCreated.</summary>
public static class DeviceSecuritySchema
{
    public static async Task InitializeAsync(PhotoSyncDbContext db)
    {
        await db.Database.EnsureCreatedAsync();
        await db.Database.OpenConnectionAsync();
        await using var transaction = await db.Database.BeginTransactionAsync();
        foreach (var (name, declaration) in new[]
        {
            ("GoogleSubject", "TEXT NULL"),
            ("GoogleEmail", "TEXT NULL"),
            ("GoogleDisplayName", "TEXT NULL")
        })
        {
            await using var check = db.Database.GetDbConnection().CreateCommand();
            check.Transaction = transaction.GetDbTransaction();
            check.CommandText = $"SELECT COUNT(*) FROM pragma_table_info('devices') WHERE name = '{name}'";
            if (Convert.ToInt64(await check.ExecuteScalarAsync()) == 0)
            {
                await using var add = db.Database.GetDbConnection().CreateCommand();
                add.Transaction = transaction.GetDbTransaction();
                // Both values come from the fixed allow-list above, never from input.
                add.CommandText = $"ALTER TABLE devices ADD COLUMN {name} {declaration}";
                await add.ExecuteNonQueryAsync();
            }
        }
        await db.Database.ExecuteSqlRawAsync("""
            CREATE TABLE IF NOT EXISTS device_credentials (
                DeviceId INTEGER NOT NULL PRIMARY KEY,
                SecretHash TEXT NOT NULL,
                FOREIGN KEY (DeviceId) REFERENCES devices (Id) ON DELETE CASCADE
            );
            DROP INDEX IF EXISTS IX_files_Sha256;
            CREATE UNIQUE INDEX IF NOT EXISTS IX_files_DeviceId_AlbumId_Sha256
                ON files (DeviceId, AlbumId, Sha256);
            CREATE INDEX IF NOT EXISTS IX_devices_GoogleSubject ON devices (GoogleSubject);
            """);
        await transaction.CommitAsync();
    }
}

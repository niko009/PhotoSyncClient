using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;

namespace PhotoSync.Server.Portal;

/// <summary>Additive upgrade for the portal database originally created with EnsureCreated.</summary>
public static class PortalSchema
{
    public static async Task InitializeAsync(PortalDbContext db)
    {
        await db.Database.EnsureCreatedAsync();
        await db.Database.OpenConnectionAsync();
        await using var transaction = await db.Database.BeginTransactionAsync();
        foreach (var (name, declaration) in new[]
        {
            ("GoogleSubject", "TEXT NULL"),
            ("DisplayName", "TEXT NULL")
        })
        {
            await using var check = db.Database.GetDbConnection().CreateCommand();
            check.Transaction = transaction.GetDbTransaction();
            check.CommandText = $"SELECT COUNT(*) FROM pragma_table_info('AspNetUsers') WHERE name = '{name}'";
            if (Convert.ToInt64(await check.ExecuteScalarAsync()) == 0)
            {
                await using var add = db.Database.GetDbConnection().CreateCommand();
                add.Transaction = transaction.GetDbTransaction();
                // Both values come from the fixed allow-list above, never from input.
                add.CommandText = $"ALTER TABLE AspNetUsers ADD COLUMN {name} {declaration}";
                await add.ExecuteNonQueryAsync();
            }
        }
        await db.Database.ExecuteSqlRawAsync("""
            CREATE UNIQUE INDEX IF NOT EXISTS IX_AspNetUsers_GoogleSubject
                ON AspNetUsers (GoogleSubject) WHERE GoogleSubject IS NOT NULL;
            """);
        await transaction.CommitAsync();
    }
}

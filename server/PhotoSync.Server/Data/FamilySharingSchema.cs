using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using PhotoSync.Server.Models;

namespace PhotoSync.Server.Data;

/// <summary>
/// Additive SQLite upgrade for the family-sharing/immutable-archive phase.
/// The project historically used EnsureCreated, so this migration deliberately
/// adds columns/tables in place and never renames, moves or deletes media files.
/// </summary>
public static class FamilySharingSchema
{
    public static async Task InitializeAsync(PhotoSyncDbContext db)
    {
        await db.Database.OpenConnectionAsync();
        await using var transaction = await db.Database.BeginTransactionAsync();

        await AddColumnIfMissingAsync(db, transaction.GetDbTransaction(), "devices", "UserId", "INTEGER NULL");
        await AddColumnIfMissingAsync(db, transaction.GetDbTransaction(), "albums", "OwnerUserId", "INTEGER NULL");
        await AddColumnIfMissingAsync(db, transaction.GetDbTransaction(), "albums", "SharingMode", "INTEGER NOT NULL DEFAULT 0");
        await AddColumnIfMissingAsync(db, transaction.GetDbTransaction(), "albums", "FamilyPermission", "INTEGER NOT NULL DEFAULT 1");
        await AddColumnIfMissingAsync(db, transaction.GetDbTransaction(), "albums", "ArchivedAtUtc", "TEXT NULL");
        await AddColumnIfMissingAsync(db, transaction.GetDbTransaction(), "files", "UploaderUserId", "INTEGER NULL");
        await AddColumnIfMissingAsync(db, transaction.GetDbTransaction(), "files", "ArchivedAtUtc", "TEXT NULL");

        await db.Database.ExecuteSqlRawAsync("""
            CREATE TABLE IF NOT EXISTS users (
                Id INTEGER NOT NULL CONSTRAINT PK_users PRIMARY KEY AUTOINCREMENT,
                GoogleSubject TEXT NOT NULL,
                GoogleEmail TEXT NOT NULL,
                GoogleDisplayName TEXT NULL,
                CreatedAtUtc TEXT NOT NULL
            );
            CREATE UNIQUE INDEX IF NOT EXISTS IX_users_GoogleSubject ON users (GoogleSubject);

            CREATE TABLE IF NOT EXISTS families (
                Id INTEGER NOT NULL CONSTRAINT PK_families PRIMARY KEY AUTOINCREMENT,
                Name TEXT NOT NULL,
                CreatedAtUtc TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS family_members (
                Id INTEGER NOT NULL CONSTRAINT PK_family_members PRIMARY KEY AUTOINCREMENT,
                FamilyId INTEGER NOT NULL,
                UserId INTEGER NOT NULL,
                Role INTEGER NOT NULL,
                IsActive INTEGER NOT NULL DEFAULT 1,
                JoinedAtUtc TEXT NOT NULL,
                RemovedAtUtc TEXT NULL,
                FOREIGN KEY (FamilyId) REFERENCES families (Id) ON DELETE RESTRICT,
                FOREIGN KEY (UserId) REFERENCES users (Id) ON DELETE RESTRICT
            );
            CREATE UNIQUE INDEX IF NOT EXISTS IX_family_members_FamilyId_UserId ON family_members (FamilyId, UserId);
            CREATE UNIQUE INDEX IF NOT EXISTS IX_family_members_UserId ON family_members (UserId);

            CREATE TABLE IF NOT EXISTS family_invitations (
                Id INTEGER NOT NULL CONSTRAINT PK_family_invitations PRIMARY KEY AUTOINCREMENT,
                FamilyId INTEGER NOT NULL,
                InvitedByUserId INTEGER NOT NULL,
                ExpectedEmail TEXT NOT NULL,
                TokenHash TEXT NOT NULL,
                CreatedAtUtc TEXT NOT NULL,
                ExpiresAtUtc TEXT NOT NULL,
                AcceptedAtUtc TEXT NULL,
                AcceptedByUserId INTEGER NULL,
                RevokedAtUtc TEXT NULL,
                FOREIGN KEY (FamilyId) REFERENCES families (Id) ON DELETE RESTRICT,
                FOREIGN KEY (InvitedByUserId) REFERENCES users (Id) ON DELETE RESTRICT
            );
            CREATE UNIQUE INDEX IF NOT EXISTS IX_family_invitations_TokenHash ON family_invitations (TokenHash);

            CREATE TABLE IF NOT EXISTS folder_acl (
                Id INTEGER NOT NULL CONSTRAINT PK_folder_acl PRIMARY KEY AUTOINCREMENT,
                AlbumId INTEGER NOT NULL,
                UserId INTEGER NOT NULL,
                Permission INTEGER NOT NULL,
                FOREIGN KEY (AlbumId) REFERENCES albums (Id) ON DELETE CASCADE,
                FOREIGN KEY (UserId) REFERENCES users (Id) ON DELETE CASCADE
            );
            CREATE UNIQUE INDEX IF NOT EXISTS IX_folder_acl_AlbumId_UserId ON folder_acl (AlbumId, UserId);

            INSERT OR IGNORE INTO users (GoogleSubject, GoogleEmail, GoogleDisplayName, CreatedAtUtc)
            SELECT GoogleSubject, COALESCE(GoogleEmail, ''), GoogleDisplayName, CURRENT_TIMESTAMP
            FROM devices
            WHERE GoogleSubject IS NOT NULL AND TRIM(GoogleSubject) <> '';

            UPDATE devices
            SET UserId = (SELECT u.Id FROM users u WHERE u.GoogleSubject = devices.GoogleSubject)
            WHERE UserId IS NULL AND GoogleSubject IS NOT NULL;

            UPDATE albums
            SET OwnerUserId = (SELECT d.UserId FROM devices d WHERE d.Id = albums.DeviceId)
            WHERE OwnerUserId IS NULL;

            UPDATE files
            SET UploaderUserId = (SELECT d.UserId FROM devices d WHERE d.Id = files.DeviceId)
            WHERE UploaderUserId IS NULL;
            """);

        await transaction.CommitAsync();

        var usersWithoutFamily = await db.Users
            .Where(u => !db.FamilyMembers.Any(m => m.UserId == u.Id))
            .ToListAsync();
        foreach (var user in usersWithoutFamily)
        {
            var family = new FamilyEntity
            {
                Name = string.IsNullOrWhiteSpace(user.GoogleDisplayName) ? "My family" : user.GoogleDisplayName + " family",
                CreatedAtUtc = DateTimeOffset.UtcNow
            };
            db.Families.Add(family);
            await db.SaveChangesAsync();
            db.FamilyMembers.Add(new FamilyMemberEntity
            {
                FamilyId = family.Id,
                UserId = user.Id,
                Role = FamilyRole.Owner,
                IsActive = true,
                JoinedAtUtc = DateTimeOffset.UtcNow
            });
            await db.SaveChangesAsync();
        }
    }

    private static async Task AddColumnIfMissingAsync(PhotoSyncDbContext db, System.Data.Common.DbTransaction transaction,
        string table, string column, string declaration)
    {
        await using var check = db.Database.GetDbConnection().CreateCommand();
        check.Transaction = transaction;
        check.CommandText = $"SELECT COUNT(*) FROM pragma_table_info('{table}') WHERE name = '{column}'";
        if (Convert.ToInt64(await check.ExecuteScalarAsync()) != 0) return;

        await using var add = db.Database.GetDbConnection().CreateCommand();
        add.Transaction = transaction;
        add.CommandText = $"ALTER TABLE {table} ADD COLUMN {column} {declaration}";
        await add.ExecuteNonQueryAsync();
    }
}

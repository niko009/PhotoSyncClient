using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Models;
using PhotoSync.Server.Options;

namespace PhotoSync.Server.Services;

public sealed class ResumableUploadService(PhotoSyncDbContext db, StoragePathResolver paths,
    FileStorageService storage, UploadGuard guard, IOptions<PhotoSyncOptions> options)
{
    public async Task<(UploadSessionEntity? Session, StoredFileEntity? Existing, string? Error)> StartAsync(
        DeviceEntity device, AlbumEntity album, ResumableUploadRequest request, CancellationToken ct)
    {
        if (request.SizeBytes < 0 || request.SizeBytes > options.Value.MaxFileBytes ||
            string.IsNullOrWhiteSpace(request.OriginalName) || string.IsNullOrWhiteSpace(request.MimeType) ||
            request.Sha256.Length != 64 || !request.Sha256.All(Uri.IsHexDigit))
            return (null, null, "INVALID_UPLOAD_METADATA");
        var hash = request.Sha256.ToLowerInvariant();
        await guard.Gate.WaitAsync(ct);
        try
        {
            var existing = await db.Files.IgnoreQueryFilters().AsNoTracking().SingleOrDefaultAsync(
                x => x.DeviceId == device.Id && x.AlbumId == album.Id && x.Sha256 == hash && x.ArchivedAtUtc == null, ct);
            if (existing is not null) return (null, existing, null);
            var session = await db.UploadSessions.SingleOrDefaultAsync(x => x.DeviceId == device.Id && x.AlbumId == album.Id && x.Sha256 == hash, ct);
            if (session is not null) return (session, null, null);
            session = new UploadSessionEntity { Id = Guid.NewGuid(), DeviceId = device.Id, AlbumId = album.Id,
                OriginalName = request.OriginalName.Trim(), MimeType = request.MimeType.Trim(), SizeBytes = request.SizeBytes,
                Sha256 = hash, CreatedAtUtc = request.CreatedAt, IsVideo = request.IsVideo,
                StartedAtUtc = DateTimeOffset.UtcNow, UpdatedAtUtc = DateTimeOffset.UtcNow };
            db.UploadSessions.Add(session);
            await db.SaveChangesAsync(ct);
            return (session, null, null);
        }
        finally { guard.Gate.Release(); }
    }

    public string TempPath(Guid id) => Path.Combine(paths.TempRoot, $"resume-{id:N}.part");

    public async Task<(UploadSessionEntity? Session, string? Error)> AppendAsync(UploadSessionEntity session, long offset, Stream content, CancellationToken ct)
    {
        if (session.StoredFileId is not null) return (session, null);
        if (offset != session.ReceivedBytes) return (session, "UPLOAD_OFFSET_MISMATCH");
        await guard.Gate.WaitAsync(ct);
        try
        {
            await db.Entry(session).ReloadAsync(ct);
            if (offset != session.ReceivedBytes) return (session, "UPLOAD_OFFSET_MISMATCH");
            Directory.CreateDirectory(paths.TempRoot);
            var path = TempPath(session.Id);
            await using var output = new FileStream(path, FileMode.OpenOrCreate, FileAccess.Write, FileShare.None, 81920, FileOptions.Asynchronous);
            if (output.Length != offset) return (session, "UPLOAD_OFFSET_MISMATCH");
            output.Seek(offset, SeekOrigin.Begin);
            var buffer = new byte[81920]; long written = 0;
            while (true) { var read = await content.ReadAsync(buffer, ct); if (read == 0) break; if (offset + written + read > session.SizeBytes) return (session, "UPLOAD_EXCEEDS_DECLARED_SIZE"); await output.WriteAsync(buffer.AsMemory(0, read), ct); written += read; }
            await output.FlushAsync(ct); output.Flush(true);
            session.ReceivedBytes = offset + written; session.UpdatedAtUtc = DateTimeOffset.UtcNow;
            await db.SaveChangesAsync(ct);
            return (session, null);
        }
        finally { guard.Gate.Release(); }
    }

    public async Task<(StoredFileEntity? File, string? Error)> CompleteAsync(UploadSessionEntity session, CancellationToken ct)
    {
        if (session.StoredFileId is int storedId)
            return (await db.Files.IgnoreQueryFilters().SingleOrDefaultAsync(x => x.Id == storedId, ct), null);
        await guard.Gate.WaitAsync(ct);
        try
        {
            await db.Entry(session).ReloadAsync(ct);
            if (session.StoredFileId is int completed) return (await db.Files.IgnoreQueryFilters().SingleOrDefaultAsync(x => x.Id == completed, ct), null);
            if (session.ReceivedBytes != session.SizeBytes) return (null, "UPLOAD_INCOMPLETE");
            var path = TempPath(session.Id);
            if (!File.Exists(path) || new FileInfo(path).Length != session.SizeBytes) return (null, "UPLOAD_INCOMPLETE");
            var device = await db.Devices.IgnoreQueryFilters().SingleAsync(x => x.Id == session.DeviceId, ct);
            var album = await db.Albums.IgnoreQueryFilters().SingleAsync(x => x.Id == session.AlbumId, ct);
            await using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, FileOptions.Asynchronous);
            // StoreAsync also serializes writes. The gate is intentionally released before it runs.
            guard.Gate.Release();
            StoreFileResult result;
            try { result = await storage.StoreAsync(new StoreFileCommand(device, album, session.OriginalName, session.MimeType, session.SizeBytes, session.Sha256, session.CreatedAtUtc, null, null, null, session.IsVideo, input), ct); }
            finally { await guard.Gate.WaitAsync(ct); }
            if (result.IsValidationError) return (null, result.IsFileTooLarge ? "FILE_TOO_LARGE" : "UPLOAD_VERIFICATION_FAILED");
            if (result.IsForbidden) return (null, "UPLOAD_FORBIDDEN");
            session.StoredFileId = result.File!.Id; session.UpdatedAtUtc = DateTimeOffset.UtcNow;
            await db.SaveChangesAsync(ct);
            File.Delete(path);
            return (result.File, null);
        }
        finally { guard.Gate.Release(); }
    }
}

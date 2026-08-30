using System.Security.Cryptography;
using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Data;
using PhotoSync.Server.Models;

namespace PhotoSync.Server.Services;

public sealed class FileStorageService(PhotoSyncDbContext dbContext, StoragePathResolver pathResolver, ILogger<FileStorageService> logger)
{
    public async Task<StoreFileResult> StoreAsync(StoreFileCommand command, CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(pathResolver.StorageRoot);
        Directory.CreateDirectory(pathResolver.TempRoot);

        var duplicate = await dbContext.Files
            .AsNoTracking()
            .SingleOrDefaultAsync(x => x.Sha256 == command.Sha256, cancellationToken);
        if (duplicate is not null)
        {
            return StoreFileResult.FromExisting(duplicate);
        }

        var tempFilePath = Path.Combine(pathResolver.TempRoot, $"{Guid.NewGuid():N}.upload");
        try
        {
            await using var source = command.FileStream;
            var buffer = new byte[81920];
            long totalBytes = 0;
            string computedHash;

            await using (var destination = new FileStream(tempFilePath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920, FileOptions.Asynchronous))
            {
                using var sha256 = SHA256.Create();
                while (true)
                {
                    var read = await source.ReadAsync(buffer, cancellationToken);
                    if (read == 0)
                    {
                        break;
                    }

                    await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                    sha256.TransformBlock(buffer, 0, read, null, 0);
                    totalBytes += read;
                }

                sha256.TransformFinalBlock([], 0, 0);
                computedHash = Convert.ToHexString(sha256.Hash!).ToLowerInvariant();
            }

            if (totalBytes != command.SizeBytes)
            {
                return StoreFileResult.Invalid($"Uploaded file size {totalBytes} does not match declared size {command.SizeBytes}.", tempFilePath);
            }

            if (!string.Equals(computedHash, command.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                return StoreFileResult.Invalid("Uploaded file hash does not match sha256.", tempFilePath);
            }

            var relativePath = await GetAvailableRelativePathAsync(command.Device, command.Album, command.CreatedAtUtc, command.OriginalName, cancellationToken);
            var finalPath = pathResolver.ToAbsolutePath(relativePath);
            Directory.CreateDirectory(Path.GetDirectoryName(finalPath)!);

            File.Move(tempFilePath, finalPath);

            var entity = new StoredFileEntity
            {
                DeviceId = command.Device.Id,
                AlbumId = command.Album.Id,
                OriginalName = command.OriginalName,
                StoredName = Path.GetFileName(finalPath),
                MimeType = command.MimeType,
                SizeBytes = totalBytes,
                Sha256 = computedHash,
                CreatedAtUtc = command.CreatedAtUtc,
                Width = command.Width,
                Height = command.Height,
                DurationMs = command.DurationMs,
                IsVideo = command.IsVideo,
                RelativePath = relativePath.Replace('\\', '/'),
                UploadedAtUtc = DateTimeOffset.UtcNow
            };

            dbContext.Files.Add(entity);
            await dbContext.SaveChangesAsync(cancellationToken);

            return StoreFileResult.Stored(entity);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to store uploaded file.");
            throw;
        }
        finally
        {
            if (File.Exists(tempFilePath))
            {
                File.Delete(tempFilePath);
            }
        }
    }

    private async Task<string> GetAvailableRelativePathAsync(DeviceEntity device, AlbumEntity album, DateTimeOffset createdAtUtc, string originalName, CancellationToken cancellationToken)
    {
        for (var index = 0; index < 5000; index++)
        {
            var suffix = index == 0 ? null : index.ToString();
            var candidate = pathResolver.GetFinalRelativePath(device, album, createdAtUtc, originalName, suffix).Replace('\\', '/');
            var existsInDb = await dbContext.Files.AsNoTracking().AnyAsync(x => x.RelativePath == candidate, cancellationToken);
            if (existsInDb)
            {
                continue;
            }

            var absolutePath = pathResolver.ToAbsolutePath(candidate);
            if (!File.Exists(absolutePath))
            {
                return candidate;
            }
        }

        throw new InvalidOperationException("Could not allocate a unique storage path for the uploaded file.");
    }
}

public sealed record StoreFileCommand(
    DeviceEntity Device,
    AlbumEntity Album,
    string OriginalName,
    string MimeType,
    long SizeBytes,
    string Sha256,
    DateTimeOffset CreatedAtUtc,
    int? Width,
    int? Height,
    long? DurationMs,
    bool IsVideo,
    Stream FileStream);

public sealed class StoreFileResult
{
    private StoreFileResult(bool success, bool alreadyExists, string? validationError, StoredFileEntity? file)
    {
        Success = success;
        AlreadyExists = alreadyExists;
        ValidationError = validationError;
        File = file;
    }

    public bool Success { get; }

    public bool AlreadyExists { get; }

    public bool IsValidationError => ValidationError is not null;

    public string? ValidationError { get; }

    public StoredFileEntity? File { get; }

    public static StoreFileResult Stored(StoredFileEntity file) => new(true, false, null, file);

    public static StoreFileResult FromExisting(StoredFileEntity file) => new(false, true, null, file);

    public static StoreFileResult Invalid(string message, string? tempFilePath = null)
    {
        if (!string.IsNullOrWhiteSpace(tempFilePath) && System.IO.File.Exists(tempFilePath))
        {
            System.IO.File.Delete(tempFilePath);
        }

        return new(false, false, message, null);
    }
}

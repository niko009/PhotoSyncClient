using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using PhotoSync.Server.Data;
using PhotoSync.Server.Options;

namespace PhotoSync.Server.Services;

/// <summary>
/// Verifies that the configured media root is the operator-provisioned mount and
/// records files which survived publication but did not receive a database row.
/// It never deletes, overwrites, or moves originals.
/// </summary>
public sealed class StorageIntegrityService(
    StoragePathResolver pathResolver,
    IOptions<PhotoSyncOptions> options,
    ILogger<StorageIntegrityService> logger)
{
    private const string RecoveryDirectoryName = "_recovery";

    public async Task InitializeAsync(PhotoSyncDbContext dbContext, CancellationToken cancellationToken = default)
    {
        await EnsureReadyAsync(cancellationToken);
        await RecordOrphanedOriginalsAsync(dbContext, cancellationToken);
    }

    public async Task<bool> IsReadyAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            await EnsureReadyAsync(cancellationToken);
            return true;
        }
        catch (StorageUnavailableException ex)
        {
            logger.LogError(ex, "Storage readiness check failed.");
            return false;
        }
    }

    public async Task EnsureReadyAsync(CancellationToken cancellationToken = default)
    {
        var root = pathResolver.StorageRoot;
        if (!Directory.Exists(root))
            throw new StorageUnavailableException("Configured media storage root is unavailable.");

        var markerName = options.Value.StorageMountMarkerFileName;
        if (string.IsNullOrWhiteSpace(markerName) || Path.GetFileName(markerName) != markerName)
            throw new StorageUnavailableException("Storage mount marker filename is invalid.");

        var markerPath = Path.Combine(root, markerName);
        string markerValue;
        try
        {
            markerValue = await File.ReadAllTextAsync(markerPath, cancellationToken);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            throw new StorageUnavailableException("Storage mount marker is unavailable.", ex);
        }

        if (!string.Equals(markerValue.Trim(), options.Value.StorageMountMarkerValue, StringComparison.Ordinal))
            throw new StorageUnavailableException("Storage mount marker does not match the configured value.");

        // Verify current write access without touching originals. A unique probe also
        // detects a mount that disappeared after the application had started.
        var tempRoot = pathResolver.TempRoot;
        var probePath = Path.Combine(tempRoot, $".readiness-{Guid.NewGuid():N}");
        try
        {
            Directory.CreateDirectory(tempRoot);
            await File.WriteAllTextAsync(probePath, "ok", cancellationToken);
            if (!string.Equals(await File.ReadAllTextAsync(probePath, cancellationToken), "ok", StringComparison.Ordinal))
                throw new IOException("Storage readiness probe could not be verified.");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            throw new StorageUnavailableException("Configured media storage is not writable.", ex);
        }
        finally
        {
            try { if (File.Exists(probePath)) File.Delete(probePath); }
            catch (Exception ex) { logger.LogWarning(ex, "Could not remove storage readiness probe {ProbePath}.", probePath); }
        }
    }

    private async Task RecordOrphanedOriginalsAsync(PhotoSyncDbContext dbContext, CancellationToken cancellationToken)
    {
        var referenced = (await dbContext.Files.IgnoreQueryFilters().AsNoTracking()
                .Select(file => file.RelativePath)
                .ToListAsync(cancellationToken))
            .Select(path => path.Replace('\\', '/'))
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        var orphaned = new List<string>();
        try
        {
            foreach (var absolutePath in Directory.EnumerateFiles(pathResolver.StorageRoot, "*", SearchOption.AllDirectories))
            {
                cancellationToken.ThrowIfCancellationRequested();
                var relative = Path.GetRelativePath(pathResolver.StorageRoot, absolutePath).Replace('\\', '/');
                if (IsControlPath(absolutePath, relative) || referenced.Contains(relative)) continue;
                orphaned.Add(relative);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            throw new StorageUnavailableException("Could not reconcile media storage.", ex);
        }

        if (orphaned.Count == 0) return;

        var recoveryRoot = Path.Combine(pathResolver.StorageRoot, RecoveryDirectoryName);
        Directory.CreateDirectory(recoveryRoot);
        var reportPath = Path.Combine(recoveryRoot, $"orphaned-originals-{DateTimeOffset.UtcNow:yyyyMMddTHHmmssfffZ}.json");
        var report = new { detected_at_utc = DateTimeOffset.UtcNow, originals = orphaned.Order(StringComparer.Ordinal).ToArray() };
        await File.WriteAllTextAsync(reportPath, JsonSerializer.Serialize(report), cancellationToken);
        logger.LogWarning("Detected {Count} unreferenced original files. Preserved them in place; recovery report: {ReportPath}.", orphaned.Count, reportPath);
    }

    private bool IsControlPath(string absolutePath, string relativePath)
    {
        var firstSegment = relativePath.Split('/', 2)[0];
        if (firstSegment is "_temp" or RecoveryDirectoryName ||
            string.Equals(firstSegment, options.Value.StorageMountMarkerFileName, StringComparison.Ordinal))
            return true;

        var databaseDirectory = Path.GetDirectoryName(Path.GetFullPath(options.Value.DatabasePath));
        var previewRoot = Path.GetFullPath(options.Value.PreviewRoot);
        return (databaseDirectory is { } directory && IsWithin(directory, pathResolver.StorageRoot) && IsWithin(absolutePath, directory)) ||
            (IsWithin(previewRoot, pathResolver.StorageRoot) && IsWithin(absolutePath, previewRoot));
    }

    private static bool IsWithin(string path, string? directory)
    {
        if (string.IsNullOrWhiteSpace(directory)) return false;
        var fullDirectory = Path.GetFullPath(directory).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var prefix = fullDirectory + Path.DirectorySeparatorChar;
        return Path.GetFullPath(path).StartsWith(prefix, OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
    }
}

public sealed class StorageUnavailableException(string message, Exception? innerException = null) : Exception(message, innerException);

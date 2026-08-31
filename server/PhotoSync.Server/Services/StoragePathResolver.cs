using Microsoft.Extensions.Options;
using System.Text;
using PhotoSync.Server.Models;
using PhotoSync.Server.Options;

namespace PhotoSync.Server.Services;

public sealed class StoragePathResolver(IOptions<PhotoSyncOptions> options)
{
    private readonly string _storageRoot = Path.GetFullPath(options.Value.StorageRoot);

    public string StorageRoot => _storageRoot;

    public string TempRoot => Path.Combine(_storageRoot, "_temp");

    public string GetAlbumRelativeDirectory(DeviceEntity device, AlbumEntity album)
        => Path.Combine("devices", device.StorageFolderName, album.StorageFolderName);

    public string GetFinalRelativePath(DeviceEntity device, AlbumEntity album, DateTimeOffset createdAtUtc, string originalName, string? suffix = null)
    {
        var fileName = MakeSafeFileName(originalName);
        var baseName = Path.GetFileNameWithoutExtension(fileName);
        var extension = Path.GetExtension(fileName);
        var effectiveName = suffix is null
            ? fileName
            : $"{baseName}_{suffix}{extension}";

        return Path.Combine(
            GetAlbumRelativeDirectory(device, album),
            createdAtUtc.UtcDateTime.ToString("yyyy"),
            createdAtUtc.UtcDateTime.ToString("MM"),
            effectiveName);
    }

    public string ToAbsolutePath(string relativePath)
    {
        var fullPath = Path.GetFullPath(Path.Combine(_storageRoot, relativePath));
        var rootPrefix = _storageRoot.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (!fullPath.StartsWith(rootPrefix, OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal))
            throw new InvalidOperationException("Storage path is outside the configured root.");
        return fullPath;
    }

    public static string MakeSafeFolderName(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "unknown";
        }

        var builder = new StringBuilder(value.Length);
        foreach (var ch in value.Trim())
        {
            builder.Append(IsUnsafe(ch) ? '_' : ch);
        }

        var collapsed = builder.ToString().Trim('.', ' ').Replace(' ', '_');
        if (string.IsNullOrWhiteSpace(collapsed)) return "unknown";
        return AvoidReservedName(collapsed.Length > 100 ? collapsed[..100] : collapsed);
    }

    public static string MakeDeviceFolderName(string deviceName, Guid deviceUuid)
        => $"{MakeSafeFolderName(deviceName)}_{deviceUuid:N}";

    public static string MakeSafeFileName(string value)
    {
        var fileName = Path.GetFileName(string.IsNullOrWhiteSpace(value) ? "file.bin" : value.Replace('\\', '/'));
        var safe = new string(fileName.Select(ch =>
            IsUnsafe(ch) ? '_' : ch).ToArray()).Trim('.', ' ');

        return string.IsNullOrWhiteSpace(safe) ? "file.bin" : AvoidReservedName(safe);
    }

    private static bool IsUnsafe(char ch) => char.IsControl(ch) || "<>:\"/\\|?*".Contains(ch);

    private static string AvoidReservedName(string value)
    {
        var stem = value.Split('.')[0].ToUpperInvariant();
        return stem is "CON" or "PRN" or "AUX" or "NUL"
            || (stem.Length == 4 && (stem.StartsWith("COM") || stem.StartsWith("LPT")) && stem[3] is >= '1' and <= '9')
            ? "_" + value : value;
    }
}

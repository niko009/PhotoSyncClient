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
        => Path.Combine(_storageRoot, relativePath);

    public static string MakeSafeFolderName(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "unknown";
        }

        var builder = new StringBuilder(value.Length);
        foreach (var ch in value.Trim())
        {
            builder.Append(Path.GetInvalidFileNameChars().Contains(ch) || char.IsControl(ch) ? '_' : ch);
        }

        var collapsed = builder.ToString().Replace(' ', '_');
        return string.IsNullOrWhiteSpace(collapsed) ? "unknown" : collapsed;
    }

    public static string MakeDeviceFolderName(string deviceName, Guid deviceUuid)
        => $"{MakeSafeFolderName(deviceName)}_{deviceUuid.ToString("N")[..8]}";

    public static string MakeSafeFileName(string value)
    {
        var fileName = Path.GetFileName(string.IsNullOrWhiteSpace(value) ? "file.bin" : value);
        var safe = new string(fileName.Select(ch =>
            Path.GetInvalidFileNameChars().Contains(ch) || char.IsControl(ch) ? '_' : ch).ToArray());

        return string.IsNullOrWhiteSpace(safe) ? "file.bin" : safe;
    }
}

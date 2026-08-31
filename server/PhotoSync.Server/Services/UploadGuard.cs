using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using PhotoSync.Server.Data;
using PhotoSync.Server.Options;

namespace PhotoSync.Server.Services;

// This deployment runs one instance. Serialize writes to make capacity checks atomic.
public sealed class UploadGuard
{
    public SemaphoreSlim Gate { get; } = new(1, 1);
    public static long? FreeBytes(string directory)
    {
        try { return new DriveInfo(OperatingSystem.IsWindows() ? Path.GetPathRoot(directory)! : directory).AvailableFreeSpace; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException) { return null; }
    }
}

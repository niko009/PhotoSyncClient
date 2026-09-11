using System.Security.Cryptography;
using PhotoSync.Server.Models;

namespace PhotoSync.Server.Services;

public static class StoredFileIntegrity
{
    public static Task<bool> VerifyAsync(StoredFileEntity file, StoragePathResolver paths, CancellationToken ct) =>
        VerifyAsync(paths.ToAbsolutePath(file.RelativePath), file.SizeBytes, file.Sha256, ct);

    public static async Task<bool> VerifyAsync(string path, long size, string hash, CancellationToken ct)
    {
        try
        {
            await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read,
                81920, FileOptions.Asynchronous | FileOptions.SequentialScan);
            if (stream.Length != size) return false;
            var actual = Convert.ToHexString(await SHA256.HashDataAsync(stream, ct));
            return string.Equals(actual, hash, StringComparison.OrdinalIgnoreCase);
        }
        catch (IOException) { return false; }
        catch (UnauthorizedAccessException) { return false; }
    }
}

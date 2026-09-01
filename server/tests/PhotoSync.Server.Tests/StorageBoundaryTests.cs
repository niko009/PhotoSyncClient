using Microsoft.Extensions.Options;
using PhotoSync.Server.Options;
using PhotoSync.Server.Services;
using Xunit;

namespace PhotoSync.Server.Tests;

public sealed class StorageBoundaryTests
{
    [Theory]
    [InlineData("..", "unknown")]
    [InlineData("../outside", "_outside")]
    [InlineData("CON", "_CON")]
    [InlineData("a:b", "a_b")]
    public void FolderNamesArePortableSingleSegments(string input, string expected)
        => Assert.Equal(expected, StoragePathResolver.MakeSafeFolderName(input));

    [Fact]
    public void DeviceFoldersCombinePhoneAndVerifiedUserName()
        => Assert.Equal("Pixel_9_Mihail_Bacus",
            StoragePathResolver.MakeDeviceOwnerFolderName("Pixel 9", "Mihail Bacus"));

    [Fact]
    public void DeviceFoldersUseLocalFallbackWithoutGoogle()
        => Assert.Equal("Pixel_9_local", StoragePathResolver.MakeDeviceOwnerFolderName("Pixel 9", null));

    [Fact]
    public void RefusesPathOutsideRoot()
    {
        var resolver = new StoragePathResolver(Microsoft.Extensions.Options.Options.Create(new PhotoSyncOptions { StorageRoot = Path.Combine(Path.GetTempPath(), "photosync-path-test") }));
        Assert.Throws<InvalidOperationException>(() => resolver.ToAbsolutePath("../outside.jpg"));
    }
}

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
    public void DeviceFoldersUseTheEntireUuid()
    {
        var first = Guid.Parse("12345678-0000-0000-0000-000000000001");
        var second = Guid.Parse("12345678-0000-0000-0000-000000000002");
        Assert.NotEqual(StoragePathResolver.MakeDeviceFolderName("Phone", first), StoragePathResolver.MakeDeviceFolderName("Phone", second));
    }

    [Fact]
    public void RefusesPathOutsideRoot()
    {
        var resolver = new StoragePathResolver(Microsoft.Extensions.Options.Options.Create(new PhotoSyncOptions { StorageRoot = Path.Combine(Path.GetTempPath(), "photosync-path-test") }));
        Assert.Throws<InvalidOperationException>(() => resolver.ToAbsolutePath("../outside.jpg"));
    }
}

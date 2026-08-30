using PhotoSync.Server.Services;

namespace PhotoSync.Server.Tests;

public sealed class PathResolverTests
{
    [Fact]
    public void MakeSafeFolderName_ReplacesInvalidCharacters()
    {
        var result = StoragePathResolver.MakeSafeFolderName("Family: Summer/2026");

        Assert.Equal("Family__Summer_2026", result);
    }
}

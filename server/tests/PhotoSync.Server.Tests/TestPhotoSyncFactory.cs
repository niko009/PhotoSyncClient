using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;

namespace PhotoSync.Server.Tests;

public sealed class TestPhotoSyncFactory : WebApplicationFactory<Program>, IAsyncDisposable
{
    private readonly string _rootPath;

    public TestPhotoSyncFactory()
    {
        _rootPath = Path.Combine(Path.GetTempPath(), "photosync-tests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_rootPath);
    }

    public string RootPath => _rootPath;

    public string StoragePath => Path.Combine(_rootPath, "storage");

    public string DatabasePath => Path.Combine(_rootPath, "photosync.test.db");

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Development");
        builder.ConfigureAppConfiguration((_, configBuilder) =>
        {
            var settings = new Dictionary<string, string?>
            {
                ["ConnectionStrings:PhotoSync"] = $"Data Source={DatabasePath}",
                ["PhotoSync:StorageRoot"] = StoragePath,
                ["PhotoSync:TempRoot"] = Path.Combine(StoragePath, "_temp"),
                ["PhotoSync:PreviewRoot"] = Path.Combine(StoragePath, "_previews"),
                ["PhotoSync:DatabasePath"] = DatabasePath,
                ["PhotoSync:ServerName"] = "Test PhotoSync"
            };

            configBuilder.AddInMemoryCollection(settings);
        });
    }

    public new async ValueTask DisposeAsync()
    {
        await base.DisposeAsync();
        if (Directory.Exists(_rootPath))
        {
            Directory.Delete(_rootPath, recursive: true);
        }
    }
}

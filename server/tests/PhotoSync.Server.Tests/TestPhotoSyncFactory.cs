using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection.Extensions;
using PhotoSync.Server.Security;

namespace PhotoSync.Server.Tests;

public sealed class TestPhotoSyncFactory : WebApplicationFactory<Program>, IAsyncDisposable
{
    private readonly string _rootPath;
    private readonly IReadOnlyDictionary<string, string?> _settings;
    private readonly IGoogleTokenVerifier? _googleVerifier;

    public TestPhotoSyncFactory(IReadOnlyDictionary<string, string?>? settings = null, IGoogleTokenVerifier? googleVerifier = null)
    {
        _settings = settings ?? new Dictionary<string, string?>();
        _googleVerifier = googleVerifier;
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
                ["ConnectionStrings:PhotoSync"] = $"Data Source={DatabasePath};Pooling=False",
                ["PhotoSync:StorageRoot"] = StoragePath,
                ["PhotoSync:TempRoot"] = Path.Combine(StoragePath, "_temp"),
                ["PhotoSync:PreviewRoot"] = Path.Combine(StoragePath, "_previews"),
                ["PhotoSync:DatabasePath"] = DatabasePath,
                ["PhotoSync:ServerName"] = "Test PhotoSync",
                ["PhotoSync:AllowDeviceEnrollment"] = "true",
                ["PhotoSync:MinFreeDiskBytes"] = "0",
                ["PhotoSync:RequestLogging:Directory"] = Path.Combine(_rootPath, "logs")
            };

            foreach (var entry in _settings) settings[entry.Key] = entry.Value;
            configBuilder.AddInMemoryCollection(settings);
        });
        if (_googleVerifier is not null)
        {
            builder.ConfigureTestServices(services =>
            {
                services.RemoveAll<IGoogleTokenVerifier>();
                services.AddSingleton(_googleVerifier);
            });
        }
    }

    public new async ValueTask DisposeAsync()
    {
        await base.DisposeAsync();
        for (var attempt = 0; attempt < 5 && Directory.Exists(_rootPath); attempt++)
        {
            try
            {
                Directory.Delete(_rootPath, recursive: true);
            }
            catch (IOException) when (attempt < 4)
            {
                // The request-log stream can take a moment to release its file
                // handle after the in-memory host has stopped on Windows.
                await Task.Delay(100 * (attempt + 1));
            }
        }
    }
}

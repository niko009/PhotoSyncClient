using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;

namespace PhotoSync.Server.Tests;

public sealed class TestServerFactory : WebApplicationFactory<Program>
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "photosync-tests", Guid.NewGuid().ToString("N"));

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        Directory.CreateDirectory(_root);

        builder.UseEnvironment("Development");
        builder.ConfigureAppConfiguration((_, config) =>
        {
            config.AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["PhotoSync:ServerName"] = "PhotoSync Test Server",
                ["PhotoSync:StorageRoot"] = Path.Combine(_root, "data"),
                ["ConnectionStrings:PhotoSync"] = $"Data Source={Path.Combine(_root, "data", "system", "photosync.db")}"
            });
        });
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);

        if (disposing && Directory.Exists(_root))
        {
            Directory.Delete(_root, true);
        }
    }
}

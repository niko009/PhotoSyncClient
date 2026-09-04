using System.Diagnostics;
using System.Text.Json;

namespace PhotoSync.Server.Services;

/// Temporary diagnostic request journal. Files rotate on the UTC hour and can
/// be disabled with PhotoSync:RequestLogging:Enabled=false before production.
public sealed class RequestAuditMiddleware
{
    private static readonly SemaphoreSlim Gate = new(1, 1);
    private readonly RequestDelegate next;
    private readonly IConfiguration configuration;
    private readonly IWebHostEnvironment environment;

    public RequestAuditMiddleware(RequestDelegate next, IConfiguration configuration, IWebHostEnvironment environment)
        => (this.next, this.configuration, this.environment) = (next, configuration, environment);

    public async Task InvokeAsync(HttpContext context)
    {
        if (!configuration.GetValue("PhotoSync:RequestLogging:Enabled", true))
        {
            await next(context);
            return;
        }

        var stopwatch = Stopwatch.StartNew();
        Exception? failure = null;
        try { await next(context); }
        catch (Exception error) { failure = error; throw; }
        finally
        {
            stopwatch.Stop();
            var root = configuration["PhotoSync:RequestLogging:Directory"];
            if (string.IsNullOrWhiteSpace(root)) root = Path.Combine(environment.ContentRootPath, "logs");
            var now = DateTimeOffset.UtcNow;
            var file = Path.Combine(root, $"requests-{now:yyyyMMddHH}.jsonl");
            var entry = new
            {
                timestamp = now,
                method = context.Request.Method,
                path = context.Request.Path.ToString(),
                status = failure is null ? context.Response.StatusCode : 500,
                durationMs = stopwatch.ElapsedMilliseconds,
                device = context.Request.Headers["X-PhotoSync-Device"].ToString(),
                userAgent = context.Request.Headers.UserAgent.ToString(),
                error = failure?.GetType().Name,
            };
            try
            {
                Directory.CreateDirectory(root);
                await Gate.WaitAsync();
                try { await File.AppendAllTextAsync(file, JsonSerializer.Serialize(entry) + Environment.NewLine); }
                finally { Gate.Release(); }
            }
            catch { /* diagnostics must never break the request */ }
        }
    }
}

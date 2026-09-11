using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace PhotoSync.Server.Services;

public sealed record RequestAuditEntry(DateTimeOffset Timestamp, string Method, string Path, int Status,
    long DurationMs, string? Device, string? RemoteIp, string? UserAgent,
    string? RequestBody, string? ResponseBody, string? Error,
    string Source = "http", string Level = "Information", string? Category = null, string? TraceId = null);

/// <summary>
/// A bounded, privacy-aware request journal for operational diagnostics.
/// Authentication secrets, Google tokens, passwords and binary payloads are never recorded.
/// </summary>
public sealed class RequestAuditMiddleware
{
    private const int RequestBodyLimit = 8 * 1024;
    private const int ResponseBodyLimit = 16 * 1024;
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

        if (context.Request.Path.StartsWithSegments("/api/portal/admin/logs")) { await next(context); return; }
        string? requestBody = null;
        try { requestBody = await ReadRequestBodyAsync(context.Request); }
        catch (IOException) { /* Let the endpoint handle transport failures. */ }
        var originalBody = context.Response.Body;
        await using var responseCapture = new BoundedCaptureStream(originalBody, ResponseBodyLimit);
        context.Response.Body = responseCapture;
        var stopwatch = Stopwatch.StartNew();
        Exception? failure = null;
        try { await next(context); }
        catch (Exception error) { failure = error; throw; }
        finally
        {
            failure ??= context.Features.Get<Microsoft.AspNetCore.Diagnostics.IExceptionHandlerFeature>()?.Error;
            stopwatch.Stop();
            context.Response.Body = originalBody;
            var entry = new RequestAuditEntry(
                DateTimeOffset.UtcNow,
                context.Request.Method,
                IsSensitive(context.Request.Path) ? "[sensitive endpoint]" : context.Request.Path.ToString(),
                failure is null ? context.Response.StatusCode : StatusCodes.Status500InternalServerError,
                stopwatch.ElapsedMilliseconds,
                EmptyToNull(context.Request.Headers["X-PhotoSync-Device"].ToString()),
                context.Connection.RemoteIpAddress?.ToString(),
                EmptyToNull(context.Request.Headers.UserAgent.ToString()),
                requestBody,
                !IsSensitive(context.Request.Path) && IsTextual(context.Response.ContentType) ? LogRedaction.Json(responseCapture.CapturedText) : null,
                LogRedaction.Clean(failure?.ToString()), "http",
                failure is not null || context.Response.StatusCode >= 500 ? "Error" : context.Response.StatusCode >= 400 ? "Warning" : "Information",
                "HTTP", context.TraceIdentifier);
            await AppendAsync(entry, configuration, environment);
        }
    }

    public static async Task<IReadOnlyList<RequestAuditEntry>> ReadLatestAsync(
        IConfiguration configuration, IWebHostEnvironment environment, int count, CancellationToken cancellationToken)
    {
        var root = ResolveRoot(configuration, environment);
        if (!Directory.Exists(root)) return [];
        count = Math.Clamp(count, 1, 500);
        var files = Directory.EnumerateFiles(root, "requests-*.jsonl")
            .OrderByDescending(Path.GetFileName).Take(24).ToArray();
        var result = new List<RequestAuditEntry>(count);
        foreach (var file in files)
        {
            var tail = new Queue<string>(count);
            await foreach (var line in File.ReadLinesAsync(file, cancellationToken))
            { if (tail.Count == count) tail.Dequeue(); tail.Enqueue(line); }
            var lines = tail.ToArray();
            for (var index = lines.Length - 1; index >= 0 && result.Count < count; index--)
            {
                try
                {
                    var item = JsonSerializer.Deserialize<RequestAuditEntry>(lines[index]);
                    if (item is not null) result.Add(item);
                }
                catch (JsonException) { }
            }
            if (result.Count >= count) break;
        }
        return result;
    }

    private static async Task<string?> ReadRequestBodyAsync(HttpRequest request)
    {
        if (IsSensitive(request.Path) || !IsTextual(request.ContentType)) return null;
        if (request.ContentLength is > RequestBodyLimit) return $"[body omitted: {request.ContentLength} bytes]";
        request.EnableBuffering();
        using var reader = new StreamReader(request.Body, Encoding.UTF8, true, 1024, leaveOpen: true);
        var buffer = new char[RequestBodyLimit];
        var read = await reader.ReadBlockAsync(buffer, 0, buffer.Length);
        request.Body.Position = 0;
        return read == 0 ? null : LogRedaction.Json(new string(buffer, 0, read));
    }

    private static bool IsSensitive(PathString path) =>
        path.StartsWithSegments("/api/portal/admin/users") || path.StartsWithSegments("/api/devices/register") ||
        path.StartsWithSegments("/api/portal/login") || path.StartsWithSegments("/api/portal/google-login") ||
        path.StartsWithSegments("/api/portal/password") || path.StartsWithSegments("/api/auth/google") ||
        path.StartsWithSegments("/api/family") || path.StartsWithSegments("/join") ||
        path.StartsWithSegments("/api/portal/csrf");

    private static bool IsTextual(string? contentType) => contentType is not null &&
        (contentType.Contains("json", StringComparison.OrdinalIgnoreCase) ||
         contentType.StartsWith("text/", StringComparison.OrdinalIgnoreCase) ||
         contentType.Contains("problem+json", StringComparison.OrdinalIgnoreCase));

    internal static async Task AppendAsync(RequestAuditEntry entry, IConfiguration configuration, IWebHostEnvironment environment)
    {
        try
        {
            var root = ResolveRoot(configuration, environment);
            var file = Path.Combine(root, $"requests-{entry.Timestamp:yyyyMMddHH}.jsonl");
            Directory.CreateDirectory(root);
            await Gate.WaitAsync();
            try
            {
                await File.AppendAllTextAsync(file, JsonSerializer.Serialize(entry) + Environment.NewLine);
                foreach (var expired in Directory.EnumerateFiles(root, "requests-*.jsonl")
                    .Where(path => File.GetLastWriteTimeUtc(path) < DateTime.UtcNow.AddDays(-Math.Clamp(configuration.GetValue("PhotoSync:RequestLogging:RetentionDays", 7), 1, 90))))
                    File.Delete(expired);
            }
            finally { Gate.Release(); }
        }
        catch { /* diagnostics must never break product requests */ }
    }

    internal static string ResolveRoot(IConfiguration configuration, IWebHostEnvironment environment)
    {
        var root = configuration["PhotoSync:RequestLogging:Directory"];
        if (string.IsNullOrWhiteSpace(root)) root = Path.Combine(environment.ContentRootPath, "logs");
        return Path.GetFullPath(root);
    }

    private static string? EmptyToNull(string value) => string.IsNullOrWhiteSpace(value) ? null : value;

    private sealed class BoundedCaptureStream(Stream destination, int limit) : Stream
    {
        private readonly MemoryStream capture = new();
        public string? CapturedText => capture.Length == 0 ? null : Encoding.UTF8.GetString(capture.ToArray());
        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => destination.Flush();
        public override Task FlushAsync(CancellationToken cancellationToken) => destination.FlushAsync(cancellationToken);
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count)
        {
            Capture(buffer.AsSpan(offset, count));
            destination.Write(buffer, offset, count);
        }
        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken cancellationToken = default)
        {
            Capture(buffer.Span);
            await destination.WriteAsync(buffer, cancellationToken);
        }
        private void Capture(ReadOnlySpan<byte> bytes)
        {
            var remaining = limit - (int)capture.Length;
            if (remaining > 0) capture.Write(bytes[..Math.Min(remaining, bytes.Length)]);
        }
    }
}

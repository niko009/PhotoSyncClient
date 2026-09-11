using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading.Channels;

namespace PhotoSync.Server.Services;

internal static class LogRedaction
{
    private static readonly Regex Secrets = new("(?i)(password|token|secret|authorization|cookie|credential|api[-_]?key)", RegexOptions.Compiled);
    public static string? Json(string? value)
    {
        if (string.IsNullOrEmpty(value)) return value;
        try
        {
            var node = JsonNode.Parse(value);
            Redact(node);
            return node?.ToJsonString();
        }
        catch (System.Text.Json.JsonException) { return "[non-JSON or truncated body omitted]"; }
    }
    private static void Redact(JsonNode? node)
    {
        if (node is JsonObject obj)
            foreach (var key in obj.Select(x => x.Key).ToArray())
                if (Secrets.IsMatch(key)) obj[key] = "[redacted]"; else Redact(obj[key]);
        else if (node is JsonArray array) foreach (var item in array) Redact(item);
    }
    public static string? Clean(string? value)
    {
        if (value is null) return null;
        value = Regex.Replace(value, @"(?i)Bearer\s+\S+", "Bearer [redacted]");
        value = Regex.Replace(value, @"(?i)((?:password|token|secret|authorization|cookie|credential|api[-_]?key)[\w-]*\s*[:=]\s*)([^\s,;]+)", "$1[redacted]");
        return value.Length > 16384 ? value[..16384] + " [truncated]" : value;
    }
}

// A bounded queue keeps disk I/O outside application logging and prevents recursive logging.
[ProviderAlias("ServerJournal")]
public sealed class ServerJournalProvider : ILoggerProvider
{
    private readonly Channel<RequestAuditEntry> queue = Channel.CreateBounded<RequestAuditEntry>(
        new BoundedChannelOptions(2048) { FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true });
    private readonly Task writer;
    private readonly IHttpContextAccessor accessor;
    public ServerJournalProvider(IConfiguration configuration, IWebHostEnvironment environment, IHttpContextAccessor accessor)
    {
        this.accessor = accessor;
        writer = Task.Run(async () =>
        {
            await foreach (var entry in queue.Reader.ReadAllAsync())
                await RequestAuditMiddleware.AppendAsync(entry, configuration, environment);
        });
    }
    public ILogger CreateLogger(string categoryName) => new JournalLogger(this, categoryName);
    public void Dispose() { queue.Writer.TryComplete(); writer.GetAwaiter().GetResult(); }
    private sealed class JournalLogger(ServerJournalProvider owner, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel level) => level != LogLevel.None;
        public void Log<TState>(LogLevel level, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(level)) return;
            var context = owner.accessor.HttpContext;
            // Reading the journal must not fill it with its own polling/SQL events.
            if (context?.Request.Path.StartsWithSegments("/api/portal/admin/logs") == true) return;
            owner.queue.Writer.TryWrite(new(DateTimeOffset.UtcNow, "", "", 0, 0, null, null, null,
                null, LogRedaction.Clean(formatter(state, exception)), LogRedaction.Clean(exception?.ToString()),
                "server", level.ToString(), category, context?.TraceIdentifier));
        }
    }
}

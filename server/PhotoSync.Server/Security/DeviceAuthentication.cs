using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using PhotoSync.Server.Data;

namespace PhotoSync.Server.Security;

public sealed class DeviceCredential
{
    public int DeviceId { get; set; }
    public string SecretHash { get; set; } = "";
}

public sealed class DeviceAuthentication(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger, UrlEncoder encoder, PhotoSyncDbContext db)
    : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "Device";
    public const string DeviceClaim = "photosync_device_id";

    public static string? ReadSecret(HttpRequest request)
    {
        var header = request.Headers.Authorization.ToString();
        if (!header.StartsWith("Bearer ", StringComparison.Ordinal)) return null;
        var secret = header[7..];
        return secret.Length == 64 && secret.All(Uri.IsHexDigit) ? secret : null;
    }

    public static string Hash(string secret) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(secret)));
    public static bool Matches(string secret, string hash) => CryptographicOperations.FixedTimeEquals(
        Encoding.ASCII.GetBytes(Hash(secret)), Encoding.ASCII.GetBytes(hash));

    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var secret = ReadSecret(Request);
        if (secret is null || !Guid.TryParse(Request.Headers["X-PhotoSync-Device"], out var uuid))
            return AuthenticateResult.NoResult();

        var device = await db.Devices.IgnoreQueryFilters().AsNoTracking()
            .SingleOrDefaultAsync(x => x.DeviceUuid == uuid, Context.RequestAborted);
        var credential = device is null ? null : await db.DeviceCredentials.AsNoTracking()
            .SingleOrDefaultAsync(x => x.DeviceId == device.Id, Context.RequestAborted);
        if (credential is null || !Matches(secret, credential.SecretHash))
            return AuthenticateResult.Fail("Invalid device credentials.");

        var identity = new ClaimsIdentity([
            new Claim(DeviceClaim, device!.Id.ToString()),
            new Claim(ClaimTypes.NameIdentifier, uuid.ToString())
        ], SchemeName);
        return AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(identity), SchemeName));
    }
}

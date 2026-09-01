using Google.Apis.Auth;
using Microsoft.Extensions.Options;

namespace PhotoSync.Server.Security;

public sealed class GoogleAuthOptions
{
    public const string SectionName = "GoogleAuth";
    public string ClientId { get; set; } = "";
}

public sealed record VerifiedGoogleIdentity(string Subject, string Email, string DisplayName);

public interface IGoogleTokenVerifier
{
    Task<VerifiedGoogleIdentity?> VerifyAsync(string idToken, CancellationToken cancellationToken);
}

public sealed class GoogleTokenVerifier(IOptions<GoogleAuthOptions> options) : IGoogleTokenVerifier
{
    public async Task<VerifiedGoogleIdentity?> VerifyAsync(string idToken, CancellationToken cancellationToken)
    {
        try
        {
            var payload = await GoogleJsonWebSignature.ValidateAsync(idToken,
                new GoogleJsonWebSignature.ValidationSettings { Audience = [options.Value.ClientId] });
            if (string.IsNullOrWhiteSpace(payload.Subject) || !payload.EmailVerified || string.IsNullOrWhiteSpace(payload.Email))
                return null;
            return new VerifiedGoogleIdentity(payload.Subject, payload.Email,
                string.IsNullOrWhiteSpace(payload.Name) ? payload.Email : payload.Name);
        }
        catch (InvalidJwtException)
        {
            return null;
        }
    }
}

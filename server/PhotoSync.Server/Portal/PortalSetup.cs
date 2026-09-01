using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.DataProtection.KeyManagement;
using Microsoft.AspNetCore.DataProtection.Repositories;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using System.Threading.RateLimiting;

namespace PhotoSync.Server.Portal;

public static class PortalSetup
{
    public const string Scheme = "Portal";
    public const string UserPolicy = "PortalUser";
    public const string AdminPolicy = "PortalAdmin";
    public const string OwnerPolicy = "PortalOwner";

    public static string SystemDirectory(IConfiguration config)
    {
        var connection = new SqliteConnectionStringBuilder(config.GetConnectionString("PhotoSync") ?? "Data Source=photosync.db");
        return Path.GetDirectoryName(Path.GetFullPath(connection.DataSource))!;
    }

    public static IServiceCollection AddPortal(this IServiceCollection services)
    {
        services.AddDbContext<PortalDbContext>((sp, options) =>
        {
            var directory = SystemDirectory(sp.GetRequiredService<IConfiguration>());
            Directory.CreateDirectory(directory);
            options.UseSqlite(new SqliteConnectionStringBuilder { DataSource = Path.Combine(directory, "photosync-portal.db"), Pooling = false }.ToString());
        });
        services.AddIdentityCore<PortalUser>(options =>
        {
            options.Password.RequiredLength = 12;
            options.Lockout.MaxFailedAccessAttempts = 5;
            options.Lockout.DefaultLockoutTimeSpan = TimeSpan.FromMinutes(15);
        }).AddRoles<IdentityRole>().AddEntityFrameworkStores<PortalDbContext>();
        services.AddAuthentication().AddCookie(Scheme, options =>
        {
            options.Cookie.Name = "__Host-PhotoSyncPortal";
            options.Cookie.HttpOnly = true;
            options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
            options.Cookie.SameSite = SameSiteMode.Strict;
            options.ExpireTimeSpan = TimeSpan.FromHours(8);
            options.SlidingExpiration = false;
            options.Events.OnRedirectToLogin = context => { context.Response.StatusCode = 401; return Task.CompletedTask; };
            options.Events.OnRedirectToAccessDenied = context => { context.Response.StatusCode = 403; return Task.CompletedTask; };
            options.Events.OnValidatePrincipal = async context =>
            {
                var manager = context.HttpContext.RequestServices.GetRequiredService<UserManager<PortalUser>>();
                var user = await manager.GetUserAsync(context.Principal!);
                if (user is null || await manager.IsLockedOutAsync(user) ||
                    context.Principal!.FindFirst("security_stamp")?.Value != user.SecurityStamp)
                    context.RejectPrincipal();
            };
        });
        services.AddAuthorization(options =>
        {
            options.AddPolicy(UserPolicy, policy => policy.AddAuthenticationSchemes(Scheme).RequireAuthenticatedUser());
            options.AddPolicy(AdminPolicy, policy => policy.AddAuthenticationSchemes(Scheme).RequireRole("ServerAdmin", "SuperAdmin"));
            options.AddPolicy(OwnerPolicy, policy => policy.AddAuthenticationSchemes(Scheme).RequireRole("SuperAdmin"));
        });
        services.AddAntiforgery(options =>
        {
            options.HeaderName = "X-PhotoSync-CSRF";
            options.Cookie.Name = "__Host-PhotoSyncCSRF";
            options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
            options.Cookie.SameSite = SameSiteMode.Strict;
        });
        services.AddDataProtection().SetApplicationName("PhotoSync.Portal");
        services.AddOptions<KeyManagementOptions>().Configure<IConfiguration, ILoggerFactory>((options, config, logger) =>
            options.XmlRepository = new FileSystemXmlRepository(new DirectoryInfo(Path.Combine(SystemDirectory(config), "keys")), logger));
        services.AddRateLimiter(options =>
        {
            options.RejectionStatusCode = 429;
            options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(_ =>
                RateLimitPartition.GetConcurrencyLimiter("server", _ => new ConcurrencyLimiterOptions { PermitLimit = 16, QueueLimit = 0 }));
            options.AddPolicy("portal-login", context => RateLimitPartition.GetFixedWindowLimiter(
                // Use the actual peer, not arbitrary forwarded IP headers.
                context.Connection.RemoteIpAddress?.ToString() ?? "unknown", _ => new FixedWindowRateLimiterOptions
                { PermitLimit = 20, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 }));
        });
        return services;
    }

    public static async Task InitializePortalAsync(this IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var sp = scope.ServiceProvider;
        await PortalSchema.InitializeAsync(sp.GetRequiredService<PortalDbContext>());
        var roles = sp.GetRequiredService<RoleManager<IdentityRole>>();
        foreach (var role in new[] { "User", "ServerAdmin", "SuperAdmin" })
            if (!await roles.RoleExistsAsync(role)) Ensure(await roles.CreateAsync(new IdentityRole(role)));
        var manager = sp.GetRequiredService<UserManager<PortalUser>>();
        if (await manager.Users.AnyAsync()) return;
        var config = sp.GetRequiredService<IConfiguration>();
        var name = config["Portal:BootstrapUser"];
        var password = config["Portal:BootstrapPassword"];
        // No default credentials, public registration or first-visitor admin claim.
        if (string.IsNullOrWhiteSpace(name) || string.IsNullOrWhiteSpace(password)) return;
        var user = new PortalUser { UserName = name };
        Ensure(await manager.CreateAsync(user, password));
        Ensure(await manager.AddToRoleAsync(user, "SuperAdmin"));
    }

    private static void Ensure(IdentityResult result)
    {
        if (!result.Succeeded) throw new InvalidOperationException("Portal bootstrap failed: " + string.Join(", ", result.Errors.Select(x => x.Code)));
    }
}

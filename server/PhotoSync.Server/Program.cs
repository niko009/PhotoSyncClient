using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Data.Sqlite;
using PhotoSync.Server.Data;
using PhotoSync.Server.Endpoints;
using PhotoSync.Server.Options;
using PhotoSync.Server.Services;
using PhotoSync.Server.Portal;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddHttpContextAccessor();
builder.Services.AddOptions<PhotoSync.Server.Security.GoogleAuthOptions>()
    .Bind(builder.Configuration.GetSection(PhotoSync.Server.Security.GoogleAuthOptions.SectionName))
    .Validate(options => !string.IsNullOrWhiteSpace(options.ClientId), "GoogleAuth:ClientId is required.")
    .ValidateOnStart();
builder.Services.AddSingleton<PhotoSync.Server.Security.IGoogleTokenVerifier, PhotoSync.Server.Security.GoogleTokenVerifier>();
builder.Services.AddPortal();
builder.Services.AddAuthentication(PhotoSync.Server.Security.DeviceAuthentication.SchemeName)
    .AddScheme<Microsoft.AspNetCore.Authentication.AuthenticationSchemeOptions, PhotoSync.Server.Security.DeviceAuthentication>(
        PhotoSync.Server.Security.DeviceAuthentication.SchemeName, _ => { });
builder.Services.AddAuthorization(options => options.FallbackPolicy =
    new Microsoft.AspNetCore.Authorization.AuthorizationPolicyBuilder().RequireAuthenticatedUser().Build());

builder.Services.AddProblemDetails();
builder.Services
    .AddOptions<PhotoSyncOptions>()
    .Bind(builder.Configuration.GetSection(PhotoSyncOptions.SectionName))
    .Validate(options => !string.IsNullOrWhiteSpace(options.StorageRoot), "PhotoSync:StorageRoot is required.")
    .Validate(options => options.MaxDevices > 0 && options.MaxFileBytes > 0 && options.MaxStorageBytes > 0 && options.MinFreeDiskBytes >= 0, "PhotoSync capacity limits must be positive.");

builder.Services.AddDbContext<PhotoSyncDbContext>((services, options) =>
{
    var connectionString = services.GetRequiredService<IConfiguration>().GetConnectionString("PhotoSync")
        ?? "Data Source=photosync.db";
    var sqlite = new SqliteConnectionStringBuilder(connectionString);
    if (!string.IsNullOrWhiteSpace(sqlite.DataSource))
    {
        var directory = Path.GetDirectoryName(sqlite.DataSource);
        if (!string.IsNullOrWhiteSpace(directory)) Directory.CreateDirectory(directory);
    }
    options.UseSqlite(connectionString);
});
builder.Services.AddSingleton<StoragePathResolver>();
builder.Services.AddScoped<FileStorageService>();
builder.Services.AddScoped<FolderAccessService>();
builder.Services.AddSingleton<UploadGuard>();
builder.Services.Configure<Microsoft.AspNetCore.Builder.ForwardedHeadersOptions>(options =>
{
    options.ForwardedHeaders = Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedProto;
    foreach (var address in builder.Configuration.GetSection("Portal:TrustedProxyAddresses").Get<string[]>() ?? [])
        options.KnownProxies.Add(System.Net.IPAddress.Parse(address));
});

var app = builder.Build();
app.UseForwardedHeaders();
app.Use(async (context, next) =>
{
    if (!context.Request.IsHttps && PhotoSync.Server.Portal.PortalSetup.IsSecurePortalRequest(context, app.Configuration))
        context.Request.Scheme = Uri.UriSchemeHttps;
    await next(context);
});

app.UseExceptionHandler(exceptionApp =>
{
    exceptionApp.Run(async context =>
    {
        var exception = context.Features.Get<IExceptionHandlerFeature>()?.Error;
        var problem = new ProblemDetails
        {
            Title = "INTERNAL_SERVER_ERROR",
            Detail = app.Environment.IsDevelopment() ? exception?.Message : "Unexpected server error.",
            Status = StatusCodes.Status500InternalServerError
        };
        problem.Extensions["code"] = "INTERNAL_SERVER_ERROR";
        context.Response.StatusCode = StatusCodes.Status500InternalServerError;
        await context.Response.WriteAsJsonAsync(problem);
    });
});

using (var scope = app.Services.CreateScope())
{
    var dbContext = scope.ServiceProvider.GetRequiredService<PhotoSyncDbContext>();
    await DeviceSecuritySchema.InitializeAsync(dbContext);
    await FamilySharingSchema.InitializeAsync(dbContext);

    var pathResolver = scope.ServiceProvider.GetRequiredService<StoragePathResolver>();
    Directory.CreateDirectory(pathResolver.StorageRoot);

    // Existing database rows may predate a temporary storage outage. Ensure every
    // active album has its physical directory before accepting requests. This also
    // makes storage permission/mount problems fail fast during container startup.
    var activeAlbums = await dbContext.Albums.IgnoreQueryFilters()
        .AsNoTracking()
        .Include(x => x.Device)
        .Where(x => x.ArchivedAtUtc == null)
        .ToListAsync();
    foreach (var album in activeAlbums)
    {
        var relativePath = pathResolver.GetAlbumRelativeDirectory(album.Device, album);
        Directory.CreateDirectory(pathResolver.ToAbsolutePath(relativePath));
    }
}

app.UseAuthentication();
await app.Services.InitializePortalAsync();
app.UseStaticFiles();
app.UseAuthorization();
app.UseRateLimiter();
app.Use(async (context, next) =>
{
    context.Response.Headers.CacheControl = "no-store";
    context.Response.Headers["X-Content-Type-Options"] = "nosniff";
    context.Response.Headers["Referrer-Policy"] = "no-referrer";
    context.Response.Headers.ContentSecurityPolicy = "default-src 'self'; script-src 'self' https://accounts.google.com/gsi/client; style-src 'self'; img-src 'self'; connect-src 'self' https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; frame-ancestors 'none'; form-action 'self'; base-uri 'none'";
    await next(context);
});

app.MapServerEndpoints();
app.MapGoogleAuthEndpoints();
app.MapFamilyEndpoints();
app.MapJoinLanding();
app.MapPortal();
app.MapGet("/health", async (PhotoSyncDbContext db) =>
    await db.Database.CanConnectAsync() ? Results.Ok(new { status = "ok", service = "photosync", protocol_version = 2 }) : Results.StatusCode(503)).AllowAnonymous();
app.MapAdminEndpoints();
app.MapDeviceEndpoints();
app.MapAlbumEndpoints();
app.MapFileEndpoints();
app.MapStatsEndpoints();

app.Run();

public partial class Program;
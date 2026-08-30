using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;
using Microsoft.Data.Sqlite;
using PhotoSync.Server.Data;
using PhotoSync.Server.Endpoints;
using PhotoSync.Server.Options;
using PhotoSync.Server.Services;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddProblemDetails();
builder.Services
    .AddOptions<PhotoSyncOptions>()
    .Bind(builder.Configuration.GetSection(PhotoSyncOptions.SectionName))
    .Validate(options => !string.IsNullOrWhiteSpace(options.StorageRoot), "PhotoSync:StorageRoot is required.");

var connectionString = builder.Configuration.GetConnectionString("PhotoSync")
    ?? "Data Source=photosync.db";

var sqliteBuilder = new SqliteConnectionStringBuilder(connectionString);
if (!string.IsNullOrWhiteSpace(sqliteBuilder.DataSource))
{
    var databaseDirectory = Path.GetDirectoryName(sqliteBuilder.DataSource);
    if (!string.IsNullOrWhiteSpace(databaseDirectory))
    {
        Directory.CreateDirectory(databaseDirectory);
    }
}

builder.Services.AddDbContext<PhotoSyncDbContext>(options => options.UseSqlite(connectionString));
builder.Services.AddSingleton<StoragePathResolver>();
builder.Services.AddScoped<FileStorageService>();

var app = builder.Build();

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
    await dbContext.Database.EnsureCreatedAsync();
}

app.MapServerEndpoints();
app.MapAdminEndpoints();
app.MapDeviceEndpoints();
app.MapAlbumEndpoints();
app.MapFileEndpoints();
app.MapGet("/api/stats/summary", async (PhotoSyncDbContext dbContext, CancellationToken cancellationToken) =>
{
    var deviceCount = await dbContext.Devices.CountAsync(cancellationToken);
    var fileCount = await dbContext.Files.CountAsync(cancellationToken);
    var bytesTotal = await dbContext.Files.SumAsync(x => (long?)x.SizeBytes, cancellationToken) ?? 0;
    var videoCount = await dbContext.Files.CountAsync(x => x.IsVideo, cancellationToken);
    var photoCount = fileCount - videoCount;

    return Results.Ok(new
    {
        device_count = deviceCount,
        file_count = fileCount,
        photo_count = photoCount,
        video_count = videoCount,
        bytes_total = bytesTotal
    });
});

app.Run();

public partial class Program;

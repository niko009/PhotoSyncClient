using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Data;
using PhotoSync.Server.Services;

namespace PhotoSync.Server.Endpoints;

public static class StatsEndpoints
{
    public static IEndpointRouteBuilder MapStatsEndpoints(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGet("/api/stats/summary", SummaryAsync);
        return endpoints;
    }

    private static async Task<IResult> SummaryAsync(PhotoSyncDbContext db, FolderAccessService access, CancellationToken ct)
    {
        var deviceCount = access.CurrentUserId is int userId
            ? await db.Devices.IgnoreQueryFilters().CountAsync(x => x.UserId == userId, ct)
            : await db.Devices.IgnoreQueryFilters().CountAsync(x => x.Id == access.CurrentDeviceId, ct);

        var candidates = await db.Files.IgnoreQueryFilters().AsNoTracking()
            .Include(x => x.Album)
            .Where(x => x.ArchivedAtUtc == null && x.Album.ArchivedAtUtc == null)
            .ToListAsync(ct);
        var visible = new List<Models.StoredFileEntity>();
        foreach (var file in candidates)
            if (await access.CanViewAsync(file.Album, ct)) visible.Add(file);

        return Results.Ok(new
        {
            device_count = deviceCount,
            file_count = visible.Count,
            photo_count = visible.Count(x => !x.IsVideo),
            video_count = visible.Count(x => x.IsVideo),
            bytes_total = visible.Sum(x => x.SizeBytes)
        });
    }
}

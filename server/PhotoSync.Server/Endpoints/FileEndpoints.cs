using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Services;

namespace PhotoSync.Server.Endpoints;

public static class FileEndpoints
{
    public static RouteGroupBuilder MapFileEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/files");
        group.MapPost("/check", CheckAsync);
        group.MapPost("/upload", UploadAsync).DisableAntiforgery();
        group.MapGet("/device/{deviceId:int}", ListForDeviceAsync);
        group.MapGet("/{fileId:int}/preview", PreviewAsync);
        group.MapGet("/{fileId:int}/download", DownloadAsync);
        group.MapPost("/{fileId:int}/archive", ArchiveAsync);
        return group;
    }

    private static async Task<IResult> CheckAsync(FileCheckRequest request, PhotoSyncDbContext db,
        FolderAccessService access, CancellationToken ct)
    {
        if (request.DeviceUuid == Guid.Empty || string.IsNullOrWhiteSpace(request.AlbumName) || string.IsNullOrWhiteSpace(request.Sha256))
            return Results.Ok(new FileCheckResponse(false));

        var album = await db.Albums.IgnoreQueryFilters().AsNoTracking()
            .Include(x => x.Device)
            .SingleOrDefaultAsync(x => x.Device.DeviceUuid == request.DeviceUuid &&
                x.AlbumName == request.AlbumName.Trim() && x.ArchivedAtUtc == null, ct);

        // Dedup is part of the upload flow. An unauthorized caller must not learn
        // whether a private hash, album, or media object exists.
        if (album is null || !await access.CanContributeAsync(album, ct))
            return Results.Ok(new FileCheckResponse(false));

        var normalizedHash = request.Sha256.Trim().ToLowerInvariant();
        var existing = await db.Files.IgnoreQueryFilters().AsNoTracking()
            .FirstOrDefaultAsync(x => x.AlbumId == album.Id && x.Sha256 == normalizedHash && x.ArchivedAtUtc == null, ct);

        return existing is null
            ? Results.Ok(new FileCheckResponse(false))
            : Results.Ok(new FileCheckResponse(true, existing.Id, existing.RelativePath));
    }

    private static async Task<IResult> UploadAsync(HttpRequest request, PhotoSyncDbContext db,
        FileStorageService storageService, FolderAccessService access, CancellationToken ct)
    {
        if (!request.HasFormContentType)
            return Results.BadRequest(ApiProblems.Validation("INVALID_CONTENT_TYPE", "multipart/form-data is required."));

        var form = await request.ReadFormAsync(ct);
        var file = form.Files.GetFile("file");
        if (file is null) return Results.BadRequest(ApiProblems.Validation("FILE_REQUIRED", "Multipart field 'file' is required."));
        if (!Guid.TryParse(form["device_uuid"], out var deviceUuid))
            return Results.BadRequest(ApiProblems.Validation("INVALID_DEVICE", "Field 'device_uuid' must be a valid UUID."));

        var albumName = form["album_name"].ToString().Trim();
        var originalName = form["original_name"].ToString().Trim();
        var mimeType = form["mime_type"].ToString().Trim();
        var sha256 = form["sha256"].ToString().Trim().ToLowerInvariant();
        if (string.IsNullOrWhiteSpace(albumName) || string.IsNullOrWhiteSpace(originalName) || string.IsNullOrWhiteSpace(mimeType) || string.IsNullOrWhiteSpace(sha256))
            return Results.BadRequest(ApiProblems.Validation("INVALID_UPLOAD_METADATA", "album_name, original_name, mime_type and sha256 are required."));
        if (!long.TryParse(form["size_bytes"], out var sizeBytes) || sizeBytes < 0)
            return Results.BadRequest(ApiProblems.Validation("INVALID_SIZE", "Field 'size_bytes' must be a non-negative integer."));
        if (!DateTimeOffset.TryParse(form["created_at"], out var createdAt))
            return Results.BadRequest(ApiProblems.Validation("INVALID_CREATED_AT", "Field 'created_at' must be a valid ISO-8601 timestamp."));

        int? width = int.TryParse(form["width"], out var parsedWidth) ? parsedWidth : null;
        int? height = int.TryParse(form["height"], out var parsedHeight) ? parsedHeight : null;
        long? durationMs = long.TryParse(form["duration_ms"], out var parsedDuration) ? parsedDuration : null;
        var isVideo = bool.TryParse(form["is_video"], out var parsedIsVideo) && parsedIsVideo;

        var device = await db.Devices.IgnoreQueryFilters().SingleOrDefaultAsync(x => x.DeviceUuid == deviceUuid, ct);
        if (device is null) return Results.NotFound(ApiProblems.NotFound("UPLOAD_TARGET_NOT_FOUND", "Upload target was not found."));
        var album = await db.Albums.IgnoreQueryFilters()
            .SingleOrDefaultAsync(x => x.DeviceId == device.Id && x.AlbumName == albumName && x.ArchivedAtUtc == null, ct);
        if (album is null || !await access.CanContributeAsync(album, ct))
            return Results.NotFound(ApiProblems.NotFound("UPLOAD_TARGET_NOT_FOUND", "Upload target was not found."));

        await using var fileStream = file.OpenReadStream();
        var result = await storageService.StoreAsync(new StoreFileCommand(device, album, originalName, mimeType, sizeBytes,
            sha256, createdAt, width, height, durationMs, isVideo, fileStream), ct);

        if (result.IsForbidden) return Results.StatusCode(StatusCodes.Status403Forbidden);
        if (result.AlreadyExists) return Results.Ok(ToUploadResponse(result.File!));
        if (result.IsValidationError)
            return Results.BadRequest(ApiProblems.Validation("UPLOAD_VERIFICATION_FAILED", result.ValidationError!));

        var stored = result.File!;
        return Results.Created($"/api/files/{stored.Id}", ToUploadResponse(stored));
    }

    private static UploadFileResponse ToUploadResponse(Models.StoredFileEntity file) =>
        new(file.Id, file.StoredName, file.RelativePath, false, file.UploadedAtUtc);

    private static async Task<IResult> ListForDeviceAsync(int deviceId, PhotoSyncDbContext db,
        FolderAccessService access, CancellationToken ct)
    {
        var candidates = await db.Files.IgnoreQueryFilters().AsNoTracking()
            .Include(x => x.Album)
            .Where(x => x.DeviceId == deviceId && x.ArchivedAtUtc == null && x.Album.ArchivedAtUtc == null)
            .ToListAsync(ct);

        var visible = new List<FileListItem>();
        foreach (var file in candidates)
        {
            if (!await access.CanViewAsync(file.Album, ct)) continue;
            visible.Add(new FileListItem(file.Id, file.Album.AlbumName, file.OriginalName, file.RelativePath,
                file.MimeType, file.SizeBytes, file.UploadedAtUtc,
                $"/api/files/{file.Id}/preview", $"/api/files/{file.Id}/download"));
        }
        return Results.Ok(new FileListResponse(visible.OrderBy(x => x.AlbumName).ThenByDescending(x => x.UploadedAt).ToList()));
    }

    private static async Task<IResult> PreviewAsync(int fileId, PhotoSyncDbContext db,
        StoragePathResolver pathResolver, FolderAccessService access, CancellationToken ct)
    {
        var file = await AuthorizedFileAsync(fileId, db, access, ct);
        if (file is null) return Results.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", "File was not found."));
        var bytes = await ReadStoredFileAsync(file, pathResolver, ct);
        return bytes is null
            ? Results.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", "File was not found."))
            : Results.File(bytes, file.MimeType);
    }

    private static async Task<IResult> DownloadAsync(int fileId, PhotoSyncDbContext db,
        StoragePathResolver pathResolver, FolderAccessService access, CancellationToken ct)
    {
        var file = await AuthorizedFileAsync(fileId, db, access, ct);
        if (file is null) return Results.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", "File was not found."));
        var bytes = await ReadStoredFileAsync(file, pathResolver, ct);
        return bytes is null
            ? Results.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", "File was not found."))
            : Results.File(bytes, file.MimeType, file.OriginalName);
    }

    private static async Task<IResult> ArchiveAsync(int fileId, PhotoSyncDbContext db, FolderAccessService access, CancellationToken ct)
    {
        var file = await db.Files.IgnoreQueryFilters().Include(x => x.Album)
            .SingleOrDefaultAsync(x => x.Id == fileId && x.ArchivedAtUtc == null, ct);
        if (file is null || !await access.CanManageAsync(file.Album, ct)) return Results.NotFound();

        // Logical removal only. The committed original at RelativePath is intentionally untouched.
        file.ArchivedAtUtc = DateTimeOffset.UtcNow;
        await db.SaveChangesAsync(ct);
        return Results.Ok(new { archived = true, original_preserved = true });
    }

    private static async Task<Models.StoredFileEntity?> AuthorizedFileAsync(int fileId, PhotoSyncDbContext db,
        FolderAccessService access, CancellationToken ct)
    {
        var file = await db.Files.IgnoreQueryFilters().AsNoTracking().Include(x => x.Album)
            .SingleOrDefaultAsync(x => x.Id == fileId && x.ArchivedAtUtc == null && x.Album.ArchivedAtUtc == null, ct);
        return file is not null && await access.CanViewAsync(file.Album, ct) ? file : null;
    }

    private static async Task<byte[]?> ReadStoredFileAsync(Models.StoredFileEntity file, StoragePathResolver pathResolver, CancellationToken ct)
    {
        var absolutePath = pathResolver.ToAbsolutePath(file.RelativePath);
        return System.IO.File.Exists(absolutePath) ? await System.IO.File.ReadAllBytesAsync(absolutePath, ct) : null;
    }
}

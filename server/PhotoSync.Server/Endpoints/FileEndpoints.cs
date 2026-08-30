using Microsoft.AspNetCore.Http.HttpResults;
using Microsoft.AspNetCore.Mvc;
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

        group.MapPost("/check", CheckAsync)
            .Produces<FileCheckResponse>(StatusCodes.Status200OK);

        group.MapPost("/upload", UploadAsync)
            .Produces<UploadFileResponse>(StatusCodes.Status201Created)
            .Produces<UploadFileResponse>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status400BadRequest)
            .ProducesProblem(StatusCodes.Status404NotFound)
            .DisableAntiforgery();

        group.MapGet("/device/{deviceId:int}", ListForDeviceAsync)
            .Produces<FileListResponse>(StatusCodes.Status200OK);

        group.MapGet("/{fileId:int}/preview", PreviewAsync)
            .Produces(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status404NotFound);

        group.MapGet("/{fileId:int}/download", DownloadAsync)
            .Produces(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status404NotFound);

        return group;
    }

    private static async Task<Ok<FileCheckResponse>> CheckAsync(
        FileCheckRequest request,
        PhotoSyncDbContext dbContext,
        CancellationToken cancellationToken)
    {
        var normalizedHash = request.Sha256.Trim().ToLowerInvariant();
        var existing = await dbContext.Files.AsNoTracking()
            .SingleOrDefaultAsync(x => x.Sha256 == normalizedHash, cancellationToken);

        return existing is null
            ? TypedResults.Ok(new FileCheckResponse(false))
            : TypedResults.Ok(new FileCheckResponse(true, existing.Id, existing.RelativePath));
    }

    private static async Task<Results<Created<UploadFileResponse>, Ok<UploadFileResponse>, BadRequest<ProblemDetails>, NotFound<ProblemDetails>>> UploadAsync(
        HttpRequest request,
        PhotoSyncDbContext dbContext,
        FileStorageService storageService,
        CancellationToken cancellationToken)
    {
        if (!request.HasFormContentType)
        {
            return TypedResults.BadRequest(ApiProblems.Validation("INVALID_CONTENT_TYPE", "multipart/form-data is required."));
        }

        var form = await request.ReadFormAsync(cancellationToken);
        var file = form.Files.GetFile("file");
        if (file is null)
        {
            return TypedResults.BadRequest(ApiProblems.Validation("FILE_REQUIRED", "Multipart field 'file' is required."));
        }

        if (!Guid.TryParse(form["device_uuid"], out var deviceUuid))
        {
            return TypedResults.BadRequest(ApiProblems.Validation("INVALID_DEVICE", "Field 'device_uuid' must be a valid UUID."));
        }

        var albumName = form["album_name"].ToString().Trim();
        var originalName = form["original_name"].ToString().Trim();
        var mimeType = form["mime_type"].ToString().Trim();
        var sha256 = form["sha256"].ToString().Trim().ToLowerInvariant();

        if (string.IsNullOrWhiteSpace(albumName) || string.IsNullOrWhiteSpace(originalName) || string.IsNullOrWhiteSpace(mimeType) || string.IsNullOrWhiteSpace(sha256))
        {
            return TypedResults.BadRequest(ApiProblems.Validation("INVALID_UPLOAD_METADATA", "album_name, original_name, mime_type and sha256 are required."));
        }

        if (!long.TryParse(form["size_bytes"], out var sizeBytes) || sizeBytes < 0)
        {
            return TypedResults.BadRequest(ApiProblems.Validation("INVALID_SIZE", "Field 'size_bytes' must be a non-negative integer."));
        }

        if (!DateTimeOffset.TryParse(form["created_at"], out var createdAt))
        {
            return TypedResults.BadRequest(ApiProblems.Validation("INVALID_CREATED_AT", "Field 'created_at' must be a valid ISO-8601 timestamp."));
        }

        int? width = int.TryParse(form["width"], out var parsedWidth) ? parsedWidth : null;
        int? height = int.TryParse(form["height"], out var parsedHeight) ? parsedHeight : null;
        long? durationMs = long.TryParse(form["duration_ms"], out var parsedDuration) ? parsedDuration : null;
        var isVideo = bool.TryParse(form["is_video"], out var parsedIsVideo) && parsedIsVideo;

        var device = await dbContext.Devices.SingleOrDefaultAsync(x => x.DeviceUuid == deviceUuid, cancellationToken);
        if (device is null)
        {
            return TypedResults.NotFound(ApiProblems.NotFound("DEVICE_NOT_FOUND", $"Device '{deviceUuid}' is not registered."));
        }

        var album = await dbContext.Albums.SingleOrDefaultAsync(x => x.DeviceId == device.Id && x.AlbumName == albumName, cancellationToken);
        if (album is null)
        {
            return TypedResults.NotFound(ApiProblems.NotFound("ALBUM_NOT_FOUND", $"Album '{albumName}' does not exist for device '{deviceUuid}'."));
        }

        await using var fileStream = file.OpenReadStream();
        var storeResult = await storageService.StoreAsync(
            new StoreFileCommand(
                device,
                album,
                originalName,
                mimeType,
                sizeBytes,
                sha256,
                createdAt,
                width,
                height,
                durationMs,
                isVideo,
                fileStream),
            cancellationToken);

        if (storeResult.AlreadyExists)
        {
            var existing = storeResult.File!;
            return TypedResults.Ok(ToUploadResponse(existing));
        }

        if (storeResult.IsValidationError)
        {
            return TypedResults.BadRequest(ApiProblems.Validation("UPLOAD_VERIFICATION_FAILED", storeResult.ValidationError!));
        }

        var stored = storeResult.File!;
        return TypedResults.Created($"/api/files/{stored.Id}", ToUploadResponse(stored));
    }

    private static UploadFileResponse ToUploadResponse(Models.StoredFileEntity file)
        => new(file.Id, file.StoredName, file.RelativePath, false, file.UploadedAtUtc);

    private static async Task<Ok<FileListResponse>> ListForDeviceAsync(
        int deviceId,
        PhotoSyncDbContext dbContext,
        CancellationToken cancellationToken)
    {
        var files = await dbContext.Files.AsNoTracking()
            .Include(x => x.Album)
            .Where(x => x.DeviceId == deviceId)
            .ToListAsync(cancellationToken);

        var response = files
            .OrderBy(x => x.Album.AlbumName)
            .ThenByDescending(x => x.CreatedAtUtc)
            .Select(x => new FileListItem(
                x.Id,
                x.Album.AlbumName,
                x.OriginalName,
                x.RelativePath,
                x.MimeType,
                x.SizeBytes,
                x.UploadedAtUtc,
                $"/api/files/{x.Id}/preview",
                $"/api/files/{x.Id}/download"))
            .ToList();

        return TypedResults.Ok(new FileListResponse(response));
    }

    private static async Task<Results<FileContentHttpResult, NotFound<ProblemDetails>>> PreviewAsync(
        int fileId,
        PhotoSyncDbContext dbContext,
        StoragePathResolver pathResolver,
        CancellationToken cancellationToken)
    {
        var file = await dbContext.Files.AsNoTracking().SingleOrDefaultAsync(x => x.Id == fileId, cancellationToken);
        if (file is null)
        {
            return TypedResults.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", $"File '{fileId}' was not found."));
        }

        var bytes = await ReadStoredFileAsync(file, pathResolver, cancellationToken);
        if (bytes is null)
        {
            return TypedResults.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", $"Stored file '{fileId}' was not found."));
        }

        return TypedResults.File(bytes, file.MimeType);
    }

    private static async Task<Results<FileContentHttpResult, NotFound<ProblemDetails>>> DownloadAsync(
        int fileId,
        PhotoSyncDbContext dbContext,
        StoragePathResolver pathResolver,
        CancellationToken cancellationToken)
    {
        var file = await dbContext.Files.AsNoTracking().SingleOrDefaultAsync(x => x.Id == fileId, cancellationToken);
        if (file is null)
        {
            return TypedResults.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", $"File '{fileId}' was not found."));
        }

        var bytes = await ReadStoredFileAsync(file, pathResolver, cancellationToken);
        if (bytes is null)
        {
            return TypedResults.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", $"Stored file '{fileId}' was not found."));
        }

        return TypedResults.File(bytes, file.MimeType, file.OriginalName);
    }

    private static async Task<byte[]?> ReadStoredFileAsync(
        Models.StoredFileEntity file,
        StoragePathResolver pathResolver,
        CancellationToken cancellationToken)
    {
        var absolutePath = pathResolver.ToAbsolutePath(file.RelativePath);
        if (!System.IO.File.Exists(absolutePath))
        {
            return null;
        }

        return await System.IO.File.ReadAllBytesAsync(absolutePath, cancellationToken);
    }
}

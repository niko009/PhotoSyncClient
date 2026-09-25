using Microsoft.EntityFrameworkCore;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Models;
using PhotoSync.Server.Services;

namespace PhotoSync.Server.Endpoints;

public static class FileEndpoints
{
    public static RouteGroupBuilder MapFileEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/files");
        group.MapPost("/check", CheckAsync);
        group.MapPost("/album/{albumId:int}/check", CheckAlbumAsync);
        group.MapPost("/upload", UploadAsync).DisableAntiforgery();
        group.MapPost("/uploads", StartResumableAsync);
        group.MapGet("/uploads/{uploadId:guid}", GetResumableStatusAsync);
        group.MapPut("/uploads/{uploadId:guid}", AppendResumableAsync).DisableAntiforgery();
        group.MapPost("/uploads/{uploadId:guid}/complete", CompleteResumableAsync);
        group.MapGet("/device/{deviceId:int}", ListForDeviceAsync);
        group.MapGet("/album/{albumId:int}", ListForAlbumAsync);
        group.MapGet("/{fileId:int}/preview", PreviewAsync);
        group.MapGet("/{fileId:int}/download", DownloadAsync);
        group.MapPost("/{fileId:int}/archive", ArchiveAsync);
        return group;
    }

    private static async Task<IResult> CheckAsync(FileCheckRequest request, PhotoSyncDbContext db,
        FolderAccessService access, StoragePathResolver paths, CancellationToken ct)
    {
        if (request.DeviceUuid == Guid.Empty || string.IsNullOrWhiteSpace(request.AlbumName) || string.IsNullOrWhiteSpace(request.Sha256))
            return Results.Ok(new FileCheckResponse(false));

        var album = await db.Albums.IgnoreQueryFilters().AsNoTracking()
            .Include(x => x.Device)
            .SingleOrDefaultAsync(x => x.Device.DeviceUuid == request.DeviceUuid &&
                x.AlbumName == request.AlbumName.Trim() && x.ArchivedAtUtc == null, ct);
        if (album is null || !OwnsDevice(access, album.Device) || !await access.CanContributeAsync(album, ct))
            return Results.Ok(new FileCheckResponse(false));

        return await DedupResponseAsync(album.Id, request.Sha256, db, paths, ct);
    }

    private static async Task<IResult> CheckAlbumAsync(int albumId, AlbumFileCheckRequest request, PhotoSyncDbContext db,
        FolderAccessService access, StoragePathResolver paths, CancellationToken ct)
    {
        var album = await db.Albums.IgnoreQueryFilters().AsNoTracking()
            .SingleOrDefaultAsync(x => x.Id == albumId && x.ArchivedAtUtc == null, ct);
        if (album is null || !await access.CanContributeAsync(album, ct))
            return Results.Ok(new FileCheckResponse(false));
        return await DedupResponseAsync(album.Id, request.Sha256, db, paths, ct);
    }

    private static async Task<IResult> DedupResponseAsync(int albumId, string sha256, PhotoSyncDbContext db, StoragePathResolver paths, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(sha256)) return Results.Ok(new FileCheckResponse(false));
        var normalizedHash = sha256.Trim().ToLowerInvariant();
        var existing = await db.Files.IgnoreQueryFilters().AsNoTracking()
            .FirstOrDefaultAsync(x => x.AlbumId == albumId && x.Sha256 == normalizedHash && x.ArchivedAtUtc == null, ct);
        return existing is null || !await StoredFileIntegrity.VerifyAsync(existing, paths, ct)
            ? Results.Ok(new FileCheckResponse(false))
            : Results.Ok(new FileCheckResponse(true, existing.Id, existing.RelativePath));
    }

    private static async Task<IResult> UploadAsync(HttpRequest request, PhotoSyncDbContext db,
        FileStorageService storageService, FolderAccessService access, ILoggerFactory loggerFactory, CancellationToken ct)
    {
        if (!request.HasFormContentType)
            return Results.BadRequest(ApiProblems.Validation("INVALID_CONTENT_TYPE", "multipart/form-data is required."));

        var logger = loggerFactory.CreateLogger("PhotoSync.Upload");
        var started = System.Diagnostics.Stopwatch.StartNew();
        // FormFeature buffers multipart sections to disk. Count transport bytes so
        // an incomplete request can be distinguished from a configured limit.
        var countedBody = new CountingReadStream(request.Body);
        request.Body = countedBody;
        IFormCollection form;
        try { form = await request.ReadFormAsync(ct); }
        catch (InvalidDataException ex) when (ex.Message.Contains("limit", StringComparison.OrdinalIgnoreCase))
        {
            logger.LogWarning("Upload rejected: request {TraceId}, expected transport bytes {ExpectedBytes}, received {ReceivedBytes}, category limit",
                request.HttpContext.TraceIdentifier, request.ContentLength, countedBody.BytesRead);
            return Results.Problem(statusCode: StatusCodes.Status413PayloadTooLarge, title: "FILE_TOO_LARGE",
                detail: "The upload exceeds the configured file size limit.",
                extensions: new Dictionary<string, object?> { ["code"] = "FILE_TOO_LARGE" });
        }
        catch (InvalidDataException ex)
        {
            logger.LogWarning(ex, "Invalid multipart upload: request {TraceId}, expected transport bytes {ExpectedBytes}, received {ReceivedBytes}, category malformed",
                request.HttpContext.TraceIdentifier, request.ContentLength, countedBody.BytesRead);
            return Results.BadRequest(ApiProblems.Validation("INVALID_MULTIPART", "The multipart upload is malformed or incomplete."));
        }
        catch (IOException ex)
        {
            logger.LogWarning(ex, "Upload interrupted: request {TraceId}, device {DeviceId}, expected transport bytes {ExpectedBytes}, received {ReceivedBytes}, cancelled {Cancelled}, category transport",
                request.HttpContext.TraceIdentifier, request.Headers["X-PhotoSync-Device"].ToString(), request.ContentLength,
                countedBody.BytesRead, request.HttpContext.RequestAborted.IsCancellationRequested);
            if (ct.IsCancellationRequested) return Results.StatusCode(499);
            return Results.Problem(statusCode: StatusCodes.Status400BadRequest, title: "UPLOAD_INTERRUPTED",
                detail: "The multipart upload ended before it was complete. Retry the file.",
                extensions: new Dictionary<string, object?> { ["code"] = "UPLOAD_INTERRUPTED" });
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            logger.LogWarning("Upload client disconnected: request {TraceId}, expected transport bytes {ExpectedBytes}, received {ReceivedBytes}, cancelled true",
                request.HttpContext.TraceIdentifier, request.ContentLength, countedBody.BytesRead);
            return Results.StatusCode(499);
        }
        var file = form.Files.GetFile("file");
        if (file is null) return Results.BadRequest(ApiProblems.Validation("FILE_REQUIRED", "Multipart field 'file' is required."));

        var originalName = form["original_name"].ToString().Trim();
        var mimeType = form["mime_type"].ToString().Trim();
        var sha256 = form["sha256"].ToString().Trim().ToLowerInvariant();
        if (string.IsNullOrWhiteSpace(originalName) || string.IsNullOrWhiteSpace(mimeType) || string.IsNullOrWhiteSpace(sha256))
            return Results.BadRequest(ApiProblems.Validation("INVALID_UPLOAD_METADATA", "original_name, mime_type and sha256 are required."));
        if (!long.TryParse(form["size_bytes"], out var sizeBytes) || sizeBytes < 0)
            return Results.BadRequest(ApiProblems.Validation("INVALID_SIZE", "Field 'size_bytes' must be a non-negative integer."));
        if (!DateTimeOffset.TryParse(form["created_at"], out var createdAt))
            return Results.BadRequest(ApiProblems.Validation("INVALID_CREATED_AT", "Field 'created_at' must be a valid ISO-8601 timestamp."));

        int? width = int.TryParse(form["width"], out var parsedWidth) ? parsedWidth : null;
        int? height = int.TryParse(form["height"], out var parsedHeight) ? parsedHeight : null;
        long? durationMs = long.TryParse(form["duration_ms"], out var parsedDuration) ? parsedDuration : null;
        var isVideo = bool.TryParse(form["is_video"], out var parsedIsVideo) && parsedIsVideo;

        AlbumEntity? album;
        DeviceEntity? device;
        if (int.TryParse(form["album_id"], out var albumId) && albumId > 0)
        {
            album = await db.Albums.IgnoreQueryFilters().Include(x => x.Device)
                .SingleOrDefaultAsync(x => x.Id == albumId && x.ArchivedAtUtc == null, ct);
            device = album?.Device;
        }
        else
        {
            if (!Guid.TryParse(form["device_uuid"], out var deviceUuid))
                return Results.BadRequest(ApiProblems.Validation("INVALID_TARGET", "Provide album_id or a valid device_uuid + album_name."));
            var albumName = form["album_name"].ToString().Trim();
            if (string.IsNullOrWhiteSpace(albumName))
                return Results.BadRequest(ApiProblems.Validation("INVALID_TARGET", "Provide album_id or a valid device_uuid + album_name."));
            device = await db.Devices.IgnoreQueryFilters().SingleOrDefaultAsync(x => x.DeviceUuid == deviceUuid, ct);
            if (device is null || !OwnsDevice(access, device))
                return Results.NotFound(ApiProblems.NotFound("UPLOAD_TARGET_NOT_FOUND", "Upload target was not found."));
            album = await db.Albums.IgnoreQueryFilters()
                .SingleOrDefaultAsync(x => x.DeviceId == device.Id && x.AlbumName == albumName && x.ArchivedAtUtc == null, ct);
        }

        if (device is null || album is null || !await access.CanContributeAsync(album, ct))
            return Results.NotFound(ApiProblems.NotFound("UPLOAD_TARGET_NOT_FOUND", "Upload target was not found."));

        await using var fileStream = file.OpenReadStream();
        var result = await storageService.StoreAsync(new StoreFileCommand(device, album, originalName, mimeType, sizeBytes,
            sha256, createdAt, width, height, durationMs, isVideo, fileStream), ct);
        logger.LogInformation("Upload {Result}: request {TraceId}, device {DeviceId}, album {AlbumId}, name {OriginalName}, expected file bytes {ExpectedBytes}, received transport bytes {ReceivedBytes}, mime {MimeType}, duration {DurationMs}ms, category {Category}",
            result.AlreadyExists ? "already_exists" : result.Success ? "stored" : "rejected",
            request.HttpContext.TraceIdentifier, device.Id, album.Id, originalName, sizeBytes,
            countedBody.BytesRead, mimeType, started.ElapsedMilliseconds,
            result.IsFileTooLarge ? "limit" : result.IsValidationError ? "validation" : result.IsForbidden ? "authorization" : "none");

        if (result.IsForbidden) return Results.StatusCode(StatusCodes.Status403Forbidden);
        if (result.AlreadyExists) return Results.Ok(ToUploadResponse(result.File!));
        if (result.IsValidationError && result.IsFileTooLarge)
            return Results.Problem(statusCode: StatusCodes.Status413PayloadTooLarge, title: "FILE_TOO_LARGE",
                detail: result.ValidationError!, extensions: new Dictionary<string, object?> { ["code"] = "FILE_TOO_LARGE" });
        if (result.IsValidationError)
            return Results.BadRequest(ApiProblems.Validation("UPLOAD_VERIFICATION_FAILED", result.ValidationError!));

        var stored = result.File!;
        return Results.Created($"/api/files/{stored.Id}", ToUploadResponse(stored));
    }

    private static UploadFileResponse ToUploadResponse(StoredFileEntity file) =>
        new(file.Id, file.StoredName, file.RelativePath, false, file.UploadedAtUtc);

    private static async Task<IResult> StartResumableAsync(ResumableUploadRequest request, PhotoSyncDbContext db,
        FolderAccessService access, ResumableUploadService uploads, CancellationToken ct)
    {
        var target = await ResolveUploadTargetAsync(request.AlbumId, request.DeviceUuid, request.AlbumName, db, access, ct);
        if (target is null) return Results.NotFound(ApiProblems.NotFound("UPLOAD_TARGET_NOT_FOUND", "Upload target was not found."));
        var (session, existing, error) = await uploads.StartAsync(target.Value.Device, target.Value.Album, request, ct);
        if (error == "FILE_TOO_LARGE")
            return Results.Problem(statusCode: StatusCodes.Status413PayloadTooLarge, title: error,
                detail: "The file exceeds the configured upload limit.",
                extensions: new Dictionary<string, object?> { ["code"] = error });
        if (error is not null) return Results.BadRequest(ApiProblems.Validation(error, "Invalid resumable upload metadata."));
        if (existing is not null) return Results.Ok(new ResumableUploadStatusResponse(Guid.Empty, existing.SizeBytes, existing.SizeBytes, ToUploadResponse(existing)));
        return Results.Ok(ToStatus(session!));
    }

    private sealed class CountingReadStream(Stream inner) : Stream
    {
        public long BytesRead { get; private set; }
        public override bool CanRead => inner.CanRead;
        public override bool CanSeek => inner.CanSeek;
        public override bool CanWrite => false;
        public override long Length => inner.Length;
        public override long Position { get => inner.Position; set => inner.Position = value; }
        public override int Read(byte[] buffer, int offset, int count)
        {
            var read = inner.Read(buffer, offset, count);
            BytesRead += read;
            return read;
        }
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            var read = await inner.ReadAsync(buffer, cancellationToken);
            BytesRead += read;
            return read;
        }
        public override async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
        {
            var read = await inner.ReadAsync(buffer, offset, count, cancellationToken);
            BytesRead += read;
            return read;
        }
        public override void Flush() => inner.Flush();
        public override long Seek(long offset, SeekOrigin origin) => inner.Seek(offset, origin);
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    private static async Task<IResult> GetResumableStatusAsync(Guid uploadId, PhotoSyncDbContext db,
        FolderAccessService access, CancellationToken ct)
    {
        var session = await db.UploadSessions.SingleOrDefaultAsync(x => x.Id == uploadId, ct);
        if (session is null || !await CanAccessSessionAsync(session, db, access, ct)) return Results.NotFound();
        return Results.Ok(await ToStatusAsync(session, db, ct));
    }

    private static async Task<IResult> AppendResumableAsync(Guid uploadId, long? offset, HttpRequest request,
        PhotoSyncDbContext db, FolderAccessService access, ResumableUploadService uploads, ILoggerFactory loggerFactory, CancellationToken ct)
    {
        if (offset is null || offset < 0) return Results.BadRequest(ApiProblems.Validation("INVALID_UPLOAD_OFFSET", "An upload offset is required."));
        var session = await db.UploadSessions.SingleOrDefaultAsync(x => x.Id == uploadId, ct);
        if (session is null || !await CanAccessSessionAsync(session, db, access, ct)) return Results.NotFound();
        var countedBody = new CountingReadStream(request.Body);
        UploadSessionEntity? updated;
        string? error;
        try { (updated, error) = await uploads.AppendAsync(session, offset.Value, countedBody, request.ContentLength, ct); }
        catch (IOException ex)
        {
            loggerFactory.CreateLogger("PhotoSync.Upload").LogWarning(ex,
                "Upload chunk interrupted: request {TraceId}, device {DeviceId}, album {AlbumId}, upload {UploadId}, offset {Offset}, expected bytes {ExpectedBytes}, received bytes {ReceivedBytes}, cancelled {Cancelled}, category transport",
                request.HttpContext.TraceIdentifier, session.DeviceId, session.AlbumId, uploadId, offset,
                request.ContentLength, countedBody.BytesRead, request.HttpContext.RequestAborted.IsCancellationRequested);
            if (ct.IsCancellationRequested) return Results.StatusCode(499);
            return Results.Problem(statusCode: StatusCodes.Status400BadRequest, title: "UPLOAD_INTERRUPTED",
                detail: "The upload chunk ended before it was complete. Retry from the reported offset.",
                extensions: new Dictionary<string, object?> { ["code"] = "UPLOAD_INTERRUPTED" });
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            loggerFactory.CreateLogger("PhotoSync.Upload").LogWarning(
                "Upload chunk client disconnected: request {TraceId}, upload {UploadId}, offset {Offset}, expected bytes {ExpectedBytes}, received bytes {ReceivedBytes}, cancelled true",
                request.HttpContext.TraceIdentifier, uploadId, offset, request.ContentLength, countedBody.BytesRead);
            return Results.StatusCode(499);
        }
        if (error == "UPLOAD_OFFSET_MISMATCH") return Results.Conflict(new { code = error, received_bytes = updated!.ReceivedBytes });
        if (error == "UPLOAD_INTERRUPTED") return Results.BadRequest(ApiProblems.Validation(error, "The upload chunk is incomplete. Retry it."));
        if (error is not null) return Results.BadRequest(ApiProblems.Validation(error, "Invalid upload chunk."));
        return Results.Ok(ToStatus(updated!));
    }

    private static async Task<IResult> CompleteResumableAsync(Guid uploadId, HttpRequest request, PhotoSyncDbContext db,
        FolderAccessService access, ResumableUploadService uploads, ILoggerFactory loggerFactory, CancellationToken ct)
    {
        var session = await db.UploadSessions.SingleOrDefaultAsync(x => x.Id == uploadId, ct);
        if (session is null || !await CanAccessSessionAsync(session, db, access, ct)) return Results.NotFound();
        var (file, error) = await uploads.CompleteAsync(session, ct);
        loggerFactory.CreateLogger("PhotoSync.Upload").LogInformation(
            "Resumable upload {Result}: request {TraceId}, upload {UploadId}, device {DeviceId}, album {AlbumId}, name {OriginalName}, expected bytes {ExpectedBytes}, received bytes {ReceivedBytes}, mime {MimeType}, duration {DurationMs}ms, category {Category}",
            error is null ? "stored" : "rejected", request.HttpContext.TraceIdentifier,
            uploadId, session.DeviceId, session.AlbumId, session.OriginalName, session.SizeBytes,
            session.ReceivedBytes, session.MimeType,
            (long)(DateTimeOffset.UtcNow - session.StartedAtUtc).TotalMilliseconds,
            error ?? "none");
        if (error == "UPLOAD_INCOMPLETE") return Results.Conflict(new { code = error, received_bytes = session.ReceivedBytes, size_bytes = session.SizeBytes });
        if (error is not null) return Results.BadRequest(ApiProblems.Validation(error, "Could not complete upload."));
        return Results.Ok(ToUploadResponse(file!));
    }

    private static ResumableUploadStatusResponse ToStatus(UploadSessionEntity session) =>
        new(session.Id, session.ReceivedBytes, session.SizeBytes);

    private static async Task<ResumableUploadStatusResponse> ToStatusAsync(UploadSessionEntity session, PhotoSyncDbContext db, CancellationToken ct) =>
        session.StoredFileId is int id
            ? new(session.Id, session.SizeBytes, session.SizeBytes, ToUploadResponse((await db.Files.IgnoreQueryFilters().SingleAsync(x => x.Id == id, ct))))
            : ToStatus(session);

    private static async Task<bool> CanAccessSessionAsync(UploadSessionEntity session, PhotoSyncDbContext db,
        FolderAccessService access, CancellationToken ct)
    {
        var album = await db.Albums.IgnoreQueryFilters().SingleOrDefaultAsync(x => x.Id == session.AlbumId, ct);
        return album is not null && await access.CanContributeAsync(album, ct);
    }

    private static async Task<(DeviceEntity Device, AlbumEntity Album)?> ResolveUploadTargetAsync(int? requestedAlbumId,
        Guid? requestedDeviceUuid, string? requestedAlbumName, PhotoSyncDbContext db, FolderAccessService access, CancellationToken ct)
    {
        AlbumEntity? album;
        DeviceEntity? device;
        if (requestedAlbumId is > 0)
        {
            album = await db.Albums.IgnoreQueryFilters().Include(x => x.Device)
                .SingleOrDefaultAsync(x => x.Id == requestedAlbumId && x.ArchivedAtUtc == null, ct);
            device = album?.Device;
        }
        else if (requestedDeviceUuid is Guid uuid && !string.IsNullOrWhiteSpace(requestedAlbumName))
        {
            device = await db.Devices.IgnoreQueryFilters().SingleOrDefaultAsync(x => x.DeviceUuid == uuid, ct);
            album = device is null ? null : await db.Albums.IgnoreQueryFilters().SingleOrDefaultAsync(
                x => x.DeviceId == device.Id && x.AlbumName == requestedAlbumName.Trim() && x.ArchivedAtUtc == null, ct);
        }
        else return null;
        return device is not null && album is not null && await access.CanContributeAsync(album, ct) ? (device, album) : null;
    }

    private static async Task<IResult> ListForDeviceAsync(int deviceId, PhotoSyncDbContext db,
        FolderAccessService access, CancellationToken ct)
    {
        var device = await db.Devices.IgnoreQueryFilters().AsNoTracking().SingleOrDefaultAsync(x => x.Id == deviceId, ct);
        if (device is null || !OwnsDevice(access, device))
            return Results.NotFound(ApiProblems.NotFound("DEVICE_NOT_FOUND", "Device was not found."));

        var files = await db.Files.IgnoreQueryFilters().AsNoTracking().Include(x => x.Album)
            .Where(x => x.DeviceId == deviceId && x.ArchivedAtUtc == null && x.Album.ArchivedAtUtc == null)
            // SQLite cannot translate DateTimeOffset ordering. IDs preserve upload order.
            .OrderBy(x => x.Album.AlbumName).ThenByDescending(x => x.Id)
            .ToListAsync(ct);
        return Results.Ok(new FileListResponse(files.Select(ToListItem).ToList()));
    }

    private static async Task<IResult> ListForAlbumAsync(int albumId, PhotoSyncDbContext db,
        FolderAccessService access, CancellationToken ct)
    {
        var album = await db.Albums.IgnoreQueryFilters().AsNoTracking()
            .SingleOrDefaultAsync(x => x.Id == albumId && x.ArchivedAtUtc == null, ct);
        if (album is null || !await access.CanViewAsync(album, ct))
            return Results.NotFound(ApiProblems.NotFound("ALBUM_NOT_FOUND", "Album was not found."));

        var files = await db.Files.IgnoreQueryFilters().AsNoTracking()
            .Where(x => x.AlbumId == albumId && x.ArchivedAtUtc == null)
            .OrderByDescending(x => x.Id)
            .ToListAsync(ct);
        return Results.Ok(new FileListResponse(files.Select(x => new FileListItem(x.Id, album.AlbumName, x.OriginalName,
            x.RelativePath, x.MimeType, x.SizeBytes, x.UploadedAtUtc,
            $"/api/files/{x.Id}/preview", $"/api/files/{x.Id}/download")).ToList()));
    }

    private static FileListItem ToListItem(StoredFileEntity file) => new(file.Id, file.Album.AlbumName,
        file.OriginalName, file.RelativePath, file.MimeType, file.SizeBytes, file.UploadedAtUtc,
        $"/api/files/{file.Id}/preview", $"/api/files/{file.Id}/download");

    private static async Task<IResult> PreviewAsync(int fileId, PhotoSyncDbContext db,
        StoragePathResolver pathResolver, FolderAccessService access, CancellationToken ct)
    {
        var file = await AuthorizedFileAsync(fileId, db, access, ct);
        if (file is null) return Results.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", "File was not found."));
        var path = pathResolver.ToExistingAbsolutePath(file.RelativePath);
        return !System.IO.File.Exists(path)
            ? Results.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", "File was not found."))
            : Results.File(path, file.MimeType, enableRangeProcessing: true);
    }

    private static async Task<IResult> DownloadAsync(int fileId, PhotoSyncDbContext db,
        StoragePathResolver pathResolver, FolderAccessService access, CancellationToken ct)
    {
        var file = await AuthorizedFileAsync(fileId, db, access, ct);
        if (file is null) return Results.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", "File was not found."));
        var path = pathResolver.ToExistingAbsolutePath(file.RelativePath);
        return !System.IO.File.Exists(path)
            ? Results.NotFound(ApiProblems.NotFound("FILE_NOT_FOUND", "File was not found."))
            : Results.File(path, file.MimeType, file.OriginalName, enableRangeProcessing: true);
    }

    private static async Task<IResult> ArchiveAsync(int fileId, PhotoSyncDbContext db, FolderAccessService access, CancellationToken ct)
    {
        var file = await db.Files.IgnoreQueryFilters().Include(x => x.Album)
            .SingleOrDefaultAsync(x => x.Id == fileId && x.ArchivedAtUtc == null, ct);
        if (file is null || !await access.CanManageAsync(file.Album, ct)) return Results.NotFound();
        file.ArchivedAtUtc = DateTimeOffset.UtcNow;
        await db.SaveChangesAsync(ct);
        return Results.Ok(new { archived = true, original_preserved = true });
    }

    private static async Task<StoredFileEntity?> AuthorizedFileAsync(int fileId, PhotoSyncDbContext db,
        FolderAccessService access, CancellationToken ct)
    {
        var file = await db.Files.IgnoreQueryFilters().AsNoTracking().Include(x => x.Album)
            .SingleOrDefaultAsync(x => x.Id == fileId && x.ArchivedAtUtc == null && x.Album.ArchivedAtUtc == null, ct);
        return file is not null && await access.CanViewAsync(file.Album, ct) ? file : null;
    }

    private static bool OwnsDevice(FolderAccessService access, DeviceEntity device) =>
        access.CurrentDeviceId == device.Id || (access.CurrentUserId is int userId && device.UserId == userId);

}

package com.photosync.android.data

import com.photosync.android.BuildConfig
import com.photosync.android.data.remote.AlbumDto
import com.photosync.android.data.remote.DeviceRegistrationDto
import com.photosync.android.data.remote.DeviceSummaryDto
import com.photosync.android.data.remote.FileItemDto
import com.photosync.android.data.remote.FileUploadResultDto
import com.photosync.android.data.remote.ServerSummaryDto
import com.photosync.android.domain.model.AccessibleAlbum
import com.photosync.android.domain.model.GoogleAccount
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.FileNotFoundException
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** A structured API failure; callers must not mistake an upload validation error for loss of connectivity. */
class PhotoSyncApiException(
    val statusCode: Int,
    val code: String?,
    val retryAfterMillis: Long? = null,
    message: String,
) : IllegalStateException(message) {
    val isTransient: Boolean get() = statusCode == 408 || statusCode == 429 || statusCode >= 500 || code == "UPLOAD_INTERRUPTED"
}

class LocalFileUnreadableException(cause: Throwable) : IOException("Local file is missing or unreadable", cause)

class PhotoSyncApiClient(
    private var baseUrl: String = DEFAULT_BASE_URL,
    private val identity: DeviceIdentity,
    private val diagnostics: DiagnosticLog? = null,
) {

    fun deviceUuid(): String = identity.credentials(effectiveBaseUrl()).first

    fun updateBaseUrl(newBaseUrl: String) {
        baseUrl = normalizeBaseUrl(newBaseUrl)
    }

    fun currentBaseUrl(): String = effectiveBaseUrl()

    fun registerDevice(
        deviceUuid: String,
        deviceName: String,
        appVersion: String,
    ): DeviceRegistrationDto {
        check(getJson("/api/server/capabilities").optBoolean("device_auth")) {
            "Update the server: private device access is required"
        }
        val payload = JSONObject()
            .put("device_uuid", deviceUuid)
            .put("device_name", deviceName)
            .put("app_version", appVersion)

        val response = postJson("/api/devices/register", payload)
        return DeviceRegistrationDto(
            deviceId = response.getInt("device_id"),
            registered = response.getBoolean("registered"),
        )
    }

    fun getSummary(): ServerSummaryDto {
        val response = getJson("/api/stats/summary")
        return ServerSummaryDto(
            deviceCount = response.getInt("device_count"),
            fileCount = response.getInt("file_count"),
            photoCount = response.getInt("photo_count"),
            videoCount = response.getInt("video_count"),
            bytesTotal = response.getLong("bytes_total"),
        )
    }

    fun getDevices(): List<DeviceSummaryDto> {
        val response = getJson("/api/devices")
        val items = response.getJSONArray("devices")
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(
                    DeviceSummaryDto(
                        id = item.getInt("id"),
                        deviceUuid = item.getString("device_uuid"),
                        deviceName = item.getString("device_name"),
                    )
                )
            }
        }
    }

    fun createAlbum(deviceUuid: String, albumName: String): AlbumDto {
        val payload = JSONObject()
            .put("device_uuid", deviceUuid)
            .put("album_name", albumName)

        val response = postJson("/api/albums", payload)
        return AlbumDto(
            id = response.getInt("album_id"),
            name = albumName,
            serverFolderPath = response.getString("server_folder_path"),
            created = response.getBoolean("created"),
        )
    }

    fun getAlbums(deviceUuid: String): List<AlbumDto> {
        val response = getJson("/api/albums?device_uuid=$deviceUuid")
        val items = response.getJSONArray("albums")
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(
                    AlbumDto(
                        id = item.getInt("album_id"),
                        name = item.getString("name"),
                        serverFolderPath = item.getString("server_folder_path"),
                        created = false,
                    )
                )
            }
        }
    }

    fun getAccessibleAlbums(): List<AccessibleAlbum> {
        val response = getJson("/api/albums/accessible")
        val items = response.getJSONArray("albums")
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(
                    AccessibleAlbum(
                        albumId = item.getInt("album_id"),
                        name = item.getString("name"),
                        permission = item.getString("permission"),
                        sharingMode = item.getString("sharing_mode"),
                        ownedByMe = item.getBoolean("owned_by_me"),
                    )
                )
            }
        }
    }

    fun getFiles(deviceId: Int): List<FileItemDto> = parseFiles(getJson("/api/files/device/$deviceId"))

    fun getFilesForAlbum(albumId: Int): List<FileItemDto> = parseFiles(getJson("/api/files/album/$albumId"))

    fun downloadFile(serverFileId: Int): ByteArray = getBytes("/api/files/$serverFileId/download")

    fun downloadPreview(serverFileId: Int): ByteArray = getBytes("/api/files/$serverFileId/preview")

    fun downloadFile(serverFileId: Int, target: File) = downloadToFile("/api/files/$serverFileId/download", target)

    fun downloadPreview(serverFileId: Int, target: File) = downloadToFile("/api/files/$serverFileId/preview", target)

    fun downloadFile(serverFileId: Int, output: OutputStream): Long =
        downloadToStream("/api/files/$serverFileId/download", output)

    private fun downloadToStream(path: String, output: OutputStream): Long {
        val connection = openConnection(path, "GET")
        try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val count = connection.inputStream.use { input -> input.copyTo(output, 64 * 1024) }
            check(connection.contentLengthLong < 0 || count == connection.contentLengthLong) { "Incomplete download" }
            return count
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToFile(path: String, target: File) {
        val connection = openConnection(path, "GET")
        val temporary = File.createTempFile("download-", ".part", target.parentFile)
        try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val count = connection.inputStream.use { input -> temporary.outputStream().use { input.copyTo(it, 64 * 1024) } }
            check(connection.contentLengthLong < 0 || count == connection.contentLengthLong) { "Incomplete download" }
            check(temporary.renameTo(target)) { "Could not publish downloaded file" }
        } finally {
            temporary.delete()
            connection.disconnect()
        }
    }

    fun googleAccount(): GoogleAccount? = getJson("/api/auth/google/me").toGoogleAccount()

    fun signInWithGoogle(idToken: String): GoogleAccount = postJson(
        "/api/auth/google/sign-in",
        JSONObject().put("id_token", idToken),
    ).toGoogleAccount() ?: error("Server did not return the linked Google account")

    fun signOutFromGoogle() {
        postJson("/api/auth/google/sign-out", JSONObject())
    }

    fun uploadFile(
        deviceUuid: String,
        albumName: String,
        originalName: String,
        mimeType: String,
        sizeBytes: Long,
        sha256: String,
        createdAtIso: String,
        openFile: () -> InputStream,
    ): FileUploadResultDto = uploadFileInternal(
        albumId = null,
        deviceUuid = deviceUuid,
        albumName = albumName,
        originalName = originalName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        createdAtIso = createdAtIso,
        openFile = openFile,
    )

    fun uploadFileToAlbum(
        albumId: Int,
        originalName: String,
        mimeType: String,
        sizeBytes: Long,
        sha256: String,
        createdAtIso: String,
        openFile: () -> InputStream,
    ): FileUploadResultDto = uploadFileInternal(
        albumId = albumId,
        deviceUuid = null,
        albumName = null,
        originalName = originalName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        createdAtIso = createdAtIso,
        openFile = openFile,
    )

    private fun uploadFileInternal(
        albumId: Int?, deviceUuid: String?, albumName: String?, originalName: String,
        mimeType: String, sizeBytes: Long, sha256: String, createdAtIso: String,
        openFile: () -> InputStream,
    ): FileUploadResultDto {
        var retry = 0
        while (true) {
            try {
                return uploadFileAttempt(albumId, deviceUuid, albumName, originalName,
                    mimeType, sizeBytes, sha256, createdAtIso, openFile)
            } catch (error: Exception) {
                val transient = when (error) {
                    is PhotoSyncApiException -> error.isTransient
                    is LocalFileUnreadableException -> false
                    is FileNotFoundException -> false
                    is IOException -> true
                    else -> false
                }
                if (!transient || retry >= 2) throw error
                val delayMillis = (error as? PhotoSyncApiException)?.retryAfterMillis
                    ?.coerceIn(1_000, 30_000) ?: (1_000L shl retry)
                Thread.sleep(delayMillis)
                retry++
            }
        }
    }

    private fun uploadFileAttempt(
        albumId: Int?,
        deviceUuid: String?,
        albumName: String?,
        originalName: String,
        mimeType: String,
        sizeBytes: Long,
        sha256: String,
        createdAtIso: String,
        openFile: () -> InputStream,
    ): FileUploadResultDto {
        // The server keys an unfinished session by device/album/SHA-256. Calling
        // start again after a process or network restart is therefore safe and
        // returns the durable offset instead of starting the media from zero.
        val start = JSONObject()
            .put("original_name", originalName)
            .put("mime_type", mimeType)
            .put("size_bytes", sizeBytes)
            .put("sha256", sha256)
            .put("created_at", createdAtIso)
            .put("is_video", mimeType.startsWith("video/"))
        if (albumId != null) start.put("album_id", albumId)
        else start.put("device_uuid", requireNotNull(deviceUuid)).put("album_name", requireNotNull(albumName))
        var status = postJson("/api/files/uploads", start)
        status.optJSONObject("completed_file")?.let { return it.toUploadResult() }
        val uploadId = status.getString("upload_id")
        var offset = status.getLong("received_bytes")
        require(offset in 0..sizeBytes) { "Server returned an invalid upload offset" }

        while (offset < sizeBytes) {
            val chunkSize = minOf(RESUMABLE_CHUNK_BYTES.toLong(), sizeBytes - offset).toInt()
            val connection = openConnection("/api/files/uploads/$uploadId?offset=$offset", "PUT")
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(chunkSize)
            try {
                connection.outputStream.use { output ->
                    openFile().use { input ->
                        input.skipFully(offset)
                        input.copyExactlyTo(output, chunkSize)
                    }
                }
                status = execute(connection)
                offset = status.getLong("received_bytes")
                require(offset <= sizeBytes) { "Server returned an invalid upload offset" }
            } finally { connection.disconnect() }
        }
        return postJson("/api/files/uploads/$uploadId/complete", JSONObject()).toUploadResult()
    }

    private fun parseFiles(response: JSONObject): List<FileItemDto> {
        val items = response.getJSONArray("files")
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(
                    FileItemDto(
                        id = item.getInt("server_file_id"),
                        albumName = item.getString("album_name"),
                        originalName = item.getString("original_name"),
                        relativePath = item.getString("relative_path"),
                        mimeType = item.getString("mime_type"),
                        sizeBytes = item.getLong("size_bytes"),
                        previewUrl = item.getString("preview_url"),
                        downloadUrl = item.getString("download_url"),
                    )
                )
            }
        }
    }

    private fun getJson(path: String): JSONObject {
        val connection = openConnection(path, "GET")
        return execute(connection)
    }

    private fun getBytes(path: String): ByteArray {
        val connection = openConnection(path, "GET")
        return executeBytes(connection)
    }

    private fun postJson(path: String, payload: JSONObject): JSONObject {
        val connection = openConnection(path, "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(payload.toString())
        }
        return execute(connection)
    }

    private fun openConnection(path: String, method: String): HttpURLConnection {
        val origin = effectiveBaseUrl()
        val connection = URL(origin + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        val (uuid, secret) = identity.credentials(origin)
        connection.setRequestProperty("X-PhotoSync-Device", uuid)
        connection.setRequestProperty("Authorization", "Bearer $secret")
        return connection
    }

    private fun execute(connection: HttpURLConnection): JSONObject {
        return try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            diagnostics?.append("${connection.requestMethod} ${connection.url.path} -> $statusCode (${body.length} bytes)")
            if (statusCode !in 200..299) {
                val problem = runCatching { JSONObject(body) }.getOrNull()
                throw PhotoSyncApiException(
                    statusCode = statusCode,
                    code = problem?.optString("code")?.takeIf { it.isNotBlank() },
                    retryAfterMillis = parseRetryAfterMillis(connection.getHeaderField("Retry-After")),
                    message = "HTTP $statusCode: ${problem?.optString("detail") ?: body}",
                )
            }

            if (body.isBlank()) JSONObject() else JSONObject(body)
        } catch (error: Exception) {
            diagnostics?.append("${connection.requestMethod} ${connection.url.path} failed: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun executeBytes(connection: HttpURLConnection): ByteArray {
        return try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            diagnostics?.append("${connection.requestMethod} ${connection.url.path} -> $statusCode (${bytes.size} bytes)")
            if (statusCode !in 200..299) {
                throw IllegalStateException("HTTP $statusCode: ${bytes.decodeToString()}")
            }
            bytes
        } catch (error: Exception) {
            diagnostics?.append("${connection.requestMethod} ${connection.url.path} failed: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRetryAfterMillis(value: String?): Long? {
        val trimmed = value?.trim().orEmpty()
        trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0) * 1_000L }
        return runCatching { (Instant.parse(trimmed).toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) }.getOrNull()
    }

    companion object {
        const val DEFAULT_BASE_URL = BuildConfig.DEFAULT_SERVER_URL
        private const val RESUMABLE_CHUNK_BYTES = 4 * 1024 * 1024
    }

    private fun effectiveBaseUrl(): String = normalizeBaseUrl(baseUrl)

    private fun normalizeBaseUrl(value: String): String {
        return ServerAddress.normalize(value.trim().ifBlank { DEFAULT_BASE_URL })
    }
}

private fun JSONObject.toGoogleAccount(): GoogleAccount? {
    if (!has("email")) return null
    return GoogleAccount(
        email = getString("email"),
        displayName = getString("display_name"),
        linkedDevices = getInt("linked_devices"),
    )
}

private fun JSONObject.toUploadResult(): FileUploadResultDto = FileUploadResultDto(
    serverFileId = getInt("server_file_id"),
    storedName = getString("stored_name"),
    relativePath = getString("relative_path"),
)

private fun InputStream.skipFully(bytes: Long) {
    var remaining = bytes
    val buffer = ByteArray(64 * 1024)
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) remaining -= skipped
        else {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(read >= 0) { "Upload source ended before resume offset" }
            remaining -= read
        }
    }
}

private fun InputStream.copyExactlyTo(output: OutputStream, bytes: Int) {
    var remaining = bytes
    val buffer = ByteArray(64 * 1024)
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        check(read >= 0) { "Upload source ended before declared size" }
        output.write(buffer, 0, read)
        remaining -= read
    }
}

package com.photosync.android.data

import com.photosync.android.BuildConfig
import com.photosync.android.data.remote.AlbumDto
import com.photosync.android.data.remote.DeviceRegistrationDto
import com.photosync.android.data.remote.DeviceSummaryDto
import com.photosync.android.data.remote.FileItemDto
import com.photosync.android.data.remote.FileUploadResultDto
import com.photosync.android.data.remote.ServerSummaryDto
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class PhotoSyncApiClient(
    private var baseUrl: String = DEFAULT_BASE_URL,
) {

    fun updateBaseUrl(newBaseUrl: String) {
        baseUrl = normalizeBaseUrl(newBaseUrl)
    }

    fun currentBaseUrl(): String = effectiveBaseUrl()

    fun registerDevice(
        deviceUuid: String,
        deviceName: String,
        appVersion: String,
    ): DeviceRegistrationDto {
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

    fun getFiles(deviceId: Int): List<FileItemDto> {
        val response = getJson("/api/files/device/$deviceId")
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

    fun downloadFile(serverFileId: Int): ByteArray = getBytes("/api/files/$serverFileId/download")

    fun downloadPreview(serverFileId: Int): ByteArray = getBytes("/api/files/$serverFileId/preview")

    fun uploadFile(
        deviceUuid: String,
        albumName: String,
        originalName: String,
        mimeType: String,
        sizeBytes: Long,
        sha256: String,
        createdAtIso: String,
        fileBytes: ByteArray,
    ): FileUploadResultDto {
        val boundary = "PhotoSyncBoundary${System.currentTimeMillis()}"
        val connection = openConnection("/api/files/upload", "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        DataOutputStream(connection.outputStream).use { output ->
            writeFormField(output, boundary, "device_uuid", deviceUuid)
            writeFormField(output, boundary, "album_name", albumName)
            writeFormField(output, boundary, "original_name", originalName)
            writeFormField(output, boundary, "mime_type", mimeType)
            writeFormField(output, boundary, "size_bytes", sizeBytes.toString())
            writeFormField(output, boundary, "sha256", sha256)
            writeFormField(output, boundary, "created_at", createdAtIso)
            writeFormField(output, boundary, "is_video", mimeType.startsWith("video/").toString())

            output.writeBytes("--$boundary\r\n")
            output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$originalName\"\r\n")
            output.writeBytes("Content-Type: $mimeType\r\n\r\n")
            output.write(fileBytes)
            output.writeBytes("\r\n--$boundary--\r\n")
            output.flush()
        }

        val response = execute(connection)
        return FileUploadResultDto(
            serverFileId = response.getInt("server_file_id"),
            storedName = response.getString("stored_name"),
            relativePath = response.getString("relative_path"),
        )
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
        val connection = URL(effectiveBaseUrl().trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    private fun execute(connection: HttpURLConnection): JSONObject {
        return try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (statusCode !in 200..299) {
                throw IllegalStateException("HTTP $statusCode: $body")
            }

            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun executeBytes(connection: HttpURLConnection): ByteArray {
        return try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (statusCode !in 200..299) {
                throw IllegalStateException("HTTP $statusCode: ${bytes.decodeToString()}")
            }
            bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun writeFormField(
        output: DataOutputStream,
        boundary: String,
        name: String,
        value: String,
    ) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        output.writeBytes(value)
        output.writeBytes("\r\n")
    }

    companion object {
        const val DEFAULT_BASE_URL = BuildConfig.DEFAULT_SERVER_URL
    }

    private fun effectiveBaseUrl(): String = normalizeBaseUrl(baseUrl)

    private fun normalizeBaseUrl(value: String): String {
        return value.trim().ifBlank { DEFAULT_BASE_URL }
    }
}

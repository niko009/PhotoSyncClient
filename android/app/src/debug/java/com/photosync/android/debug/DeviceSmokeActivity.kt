package com.photosync.android.debug

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.photosync.android.data.DeviceIdentity
import com.photosync.android.data.PhotoSyncApiClient
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** ADB-triggered, debug-only end-to-end probe. It never uses production app state. */
class DeviceSmokeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "PhotoSync device smoke test is running…" }
        setContentView(status)

        val origin = intent.getStringExtra(EXTRA_SERVER_URL) ?: "http://127.0.0.1:5187"
        Thread {
            val result = runCatching { runProbe(origin) }
                .fold(
                    onSuccess = { JSONObject().put("ok", true).put("details", it) },
                    onFailure = { error ->
                        Log.e(TAG, "Device smoke test failed", error)
                        JSONObject()
                            .put("ok", false)
                            .put("error", error.javaClass.name)
                            .put("message", error.message)
                    },
                )
                .put("finished_at", Instant.now().toString())

            val output = File(getExternalFilesDir("diagnostics"), RESULT_FILE)
            output.parentFile?.mkdirs()
            output.writeText(result.toString(2))
            Log.i(TAG, "RESULT ${result}")
            runOnUiThread {
                status.text = if (result.getBoolean("ok")) "PhotoSync device smoke test passed" else "PhotoSync device smoke test failed"
                setResult(if (result.getBoolean("ok")) RESULT_OK else RESULT_CANCELED)
                finish()
            }
        }.start()
    }

    private fun runProbe(origin: String): JSONObject {
        val firstContext = IsolatedContext(this)
        val secondContext = IsolatedContext(this)
        try {
            val firstIdentity = DeviceIdentity(firstContext)
            val secondIdentity = DeviceIdentity(secondContext)
            val firstApi = PhotoSyncApiClient(origin, firstIdentity)
            val secondApi = PhotoSyncApiClient(origin, secondIdentity)

            val firstRegistration = firstApi.registerDevice(firstApi.deviceUuid(), "Physical phone smoke A", "debug")
            secondApi.registerDevice(secondApi.deviceUuid(), "Physical phone smoke B", "debug")
            check(firstIdentity.credentials(origin) == DeviceIdentity(firstContext).credentials(origin)) {
                "Device identity changed between client instances"
            }

            val album = firstApi.createAlbum(firstApi.deviceUuid(), "Physical phone smoke ${System.currentTimeMillis()}")
            val small = ByteArray(256 * 1024) { index -> (index * 31).toByte() }
            val smallFile = File(firstContext.filesDir, "fixture.jpg").apply { writeBytes(small) }
            val smallUpload = firstApi.uploadFileToAlbum(
                albumId = album.id,
                originalName = smallFile.name,
                mimeType = "image/jpeg",
                sizeBytes = smallFile.length(),
                sha256 = sha256(smallFile),
                createdAtIso = Instant.now().toString(),
                openFile = smallFile::inputStream,
            )
            check(firstApi.downloadFile(smallUpload.serverFileId).contentEquals(small)) { "Small-file round trip mismatch" }

            val videoSize = 32L * 1024 * 1024
            val videoFile = File(firstContext.filesDir, "large-video.mp4")
            videoFile.outputStream().buffered().use { output ->
                val block = ByteArray(64 * 1024) { index -> (index * 17 + 11).toByte() }
                repeat((videoSize / block.size).toInt()) { output.write(block) }
            }
            val videoHash = sha256(videoFile)
            val videoUpload = firstApi.uploadFileToAlbum(
                albumId = album.id,
                originalName = videoFile.name,
                mimeType = "video/mp4",
                sizeBytes = videoFile.length(),
                sha256 = videoHash,
                createdAtIso = Instant.now().toString(),
                openFile = videoFile::inputStream,
            )
            val downloadedVideo = File(firstContext.filesDir, "downloaded-video.mp4")
            firstApi.downloadFile(videoUpload.serverFileId, downloadedVideo)
            check(downloadedVideo.length() == videoSize && sha256(downloadedVideo) == videoHash) {
                "Streaming video round trip mismatch"
            }
            checkRange(origin, firstIdentity, videoUpload.serverFileId)

            val albumFiles = firstApi.getFilesForAlbum(album.id)
            check(albumFiles.size == 2) { "Expected two uploaded files, found ${albumFiles.size}" }
            check(firstApi.getFiles(firstRegistration.deviceId).size == 2) { "Device file listing mismatch" }
            check(firstApi.getSummary().fileCount == 2) { "First device summary mismatch" }
            check(secondApi.getSummary().fileCount == 0) { "Second device can see first device statistics" }
            check(runCatching { secondApi.getFiles(firstRegistration.deviceId) }.isFailure) {
                "Second device can list first device files"
            }
            check(runCatching { secondApi.downloadFile(smallUpload.serverFileId) }.isFailure) {
                "Second device can download first device files"
            }

            return JSONObject()
                .put("server", origin)
                .put("small_bytes", small.size)
                .put("video_bytes", videoSize)
                .put("range_response", 206)
                .put("album_files", albumFiles.size)
                .put("device_isolation", true)
        } finally {
            firstContext.cleanUp()
            secondContext.cleanUp()
        }
    }

    private fun checkRange(origin: String, identity: DeviceIdentity, fileId: Int) {
        val connection = URL("$origin/api/files/$fileId/download").openConnection() as HttpURLConnection
        val (uuid, secret) = identity.credentials(origin)
        connection.setRequestProperty("X-PhotoSync-Device", uuid)
        connection.setRequestProperty("Authorization", "Bearer $secret")
        connection.setRequestProperty("Range", "bytes=1048576-2097151")
        try {
            check(connection.responseCode == 206) { "Range request returned HTTP ${connection.responseCode}" }
            check(connection.getHeaderField("Content-Range")?.startsWith("bytes 1048576-2097151/") == true) {
                "Range response is missing Content-Range"
            }
            check(connection.inputStream.use { it.readBytes() }.size == 1024 * 1024) { "Range payload size mismatch" }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val root = File(base.cacheDir, "device-smoke-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${root.name}-$name", mode)
        fun cleanUp() { root.deleteRecursively() }
    }

    companion object {
        const val EXTRA_SERVER_URL = "serverUrl"
        const val RESULT_FILE = "device-smoke-result.json"
        private const val TAG = "PhotoSyncDeviceSmoke"
    }
}

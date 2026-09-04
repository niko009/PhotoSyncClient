package com.photosync.android.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.photosync.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val UPDATE_MANIFEST_URL = "https://bacus.dev/downloads/photosync/latest.json"
private const val APK_MIME = "application/vnd.android.package-archive"

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long?,
)

data class UpdateDownloadStatus(
    val state: Int,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
)

class AppUpdateManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)

    suspend fun checkForUpdate(): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("Update manifest HTTP ${connection.responseCode}")
                }
                val payload = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(payload)
                val info = AppUpdateInfo(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.getString("versionName"),
                    apkUrl = json.getString("apkUrl"),
                    sha256 = json.getString("sha256").lowercase(),
                    sizeBytes = json.optLong("sizeBytes").takeIf { it > 0 },
                )
                if (isNewerVersion(info.versionCode, BuildConfig.VERSION_CODE)) info else null
            } finally {
                connection.disconnect()
            }
        }
    }

    fun enqueueDownload(info: AppUpdateInfo): Long {
        val fileName = "photosync-${info.versionName}.apk"
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("PhotoSync ${info.versionName}")
            .setDescription("PhotoSync update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { destinationDir ->
            destinationDir.mkdirs()
            File(destinationDir, fileName).delete()
            request.setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                fileName,
            )
        }

        return downloadManager.enqueue(request)
    }

    fun queryDownload(downloadId: Long): UpdateDownloadStatus? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return UpdateDownloadStatus(state, downloaded, total)
        }
        return null
    }

    suspend fun verifyDownloadedApk(downloadId: Long, info: AppUpdateInfo): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = downloadManager.getUriForDownloadedFile(downloadId)
                ?: error("Downloaded APK is unavailable")
            val digest = MessageDigest.getInstance("SHA-256")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: error("Downloaded APK cannot be read")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(info.sha256, ignoreCase = true)) {
                "Downloaded APK checksum mismatch"
            }
            uri
        }.onFailure {
            downloadManager.remove(downloadId)
        }
    }

    fun requestInstall(apkUri: Uri): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(settingsIntent)
            return false
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.startActivity(installIntent)
        return true
    }
}

internal fun isNewerVersion(remoteVersionCode: Int, installedVersionCode: Int): Boolean =
    remoteVersionCode > installedVersionCode

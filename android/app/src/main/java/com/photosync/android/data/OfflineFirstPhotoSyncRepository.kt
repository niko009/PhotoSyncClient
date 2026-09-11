package com.photosync.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.photosync.android.domain.model.DashboardStats
import com.photosync.android.domain.model.FolderDetail
import com.photosync.android.domain.model.FolderSummary
import com.photosync.android.domain.model.PhotoItem
import com.photosync.android.domain.model.PhotoSyncStatus
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Local-first media queue.
 *
 * Adding media succeeds as soon as PhotoSync has a durable local reference/copy.
 * Server upload is a second step and may happen immediately or later when a
 * validated network becomes available. This makes folder/media creation usable
 * without Internet access while keeping the existing network repository as the
 * server source of truth.
 */
class OfflineFirstPhotoSyncRepository(
    context: Context,
    private val delegate: PhotoSyncRepository,
) : PhotoSyncRepository by delegate {
    private val appContext = context.applicationContext
    private val queueMutex = Mutex()
    private val queue = MutableStateFlow(loadQueue())

    init {
        if (queue.value.isNotEmpty()) {
            OfflineSyncScheduler.enqueue(appContext)
        }
    }

    override fun observeFolders(): Flow<List<FolderSummary>> = combine(
        delegate.observeFolders(),
        queue.asStateFlow(),
    ) { baseFolders, pending ->
        val pendingByFolder = pending.groupingBy { it.folderId }.eachCount()
        baseFolders.map { folder ->
            val pendingCount = pendingByFolder[folder.id] ?: 0
            if (pendingCount == 0) {
                folder
            } else {
                folder.copy(
                    photoCount = folder.photoCount + pendingCount,
                    pendingCount = folder.pendingCount + pendingCount,
                    statusLabel = if (folder.failedCount > 0) folder.statusLabel else "Pending sync",
                )
            }
        }
    }

    override fun observeFolder(folderId: String): Flow<FolderDetail?> = combine(
        delegate.observeFolder(folderId),
        queue.asStateFlow(),
    ) { baseFolder, pending ->
        baseFolder?.let { folder ->
            val queued = pending.filter { it.folderId == folderId }
            if (queued.isEmpty()) return@let folder

            // A previous online attempt can leave a temporary Failed record in
            // the delegate. Hide that duplicate while the durable offline queue
            // owns the retry for the same media item.
            val withoutTemporaryDuplicates = folder.photos.filterNot { photo ->
                photo.serverFileId == null && queued.any { item -> item.matches(photo) }
            }
            folder.copy(
                photos = withoutTemporaryDuplicates + queued.map { it.toPhotoItem() },
            )
        }
    }

    override fun observeStats(): Flow<DashboardStats> = combine(
        observeFolders(),
        delegate.observeStats(),
    ) { visibleFolders, baseStats ->
        baseStats.copy(
            totalFolders = visibleFolders.size,
            totalPhotos = visibleFolders.sumOf { it.photoCount },
            syncedPhotos = visibleFolders.sumOf { it.syncedCount },
            pendingPhotos = visibleFolders.sumOf { it.pendingCount },
            failedPhotos = visibleFolders.sumOf { it.failedCount },
        )
    }

    override suspend fun addFolder(name: String) {
        // The base repository already creates the local folder first, even if
        // the server is unreachable. Schedule persistent background recovery.
        delegate.addFolder(name)
        OfflineSyncScheduler.enqueue(appContext)
    }

    override suspend fun uploadToFolder(folderId: String, uri: Uri): Boolean = queueMutex.withLock {
        val folder = delegate.observeFolder(folderId).first() ?: return@withLock false
        if (!folder.canContribute) return@withLock false

        val queuedItem = runCatching { withContext(Dispatchers.IO) { createQueueItem(folderId, uri) } }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { error -> Log.e(TAG, "Could not queue media for offline sync", error) }
            .getOrNull() ?: return@withLock false

        val nextQueue = queue.value + queuedItem
        persistQueue(nextQueue)
        queue.value = nextQueue
        OfflineSyncScheduler.enqueue(appContext)

        // Local acceptance is success. Network synchronization is deliberately
        // best-effort so users can keep working without Internet.
        if (hasNetwork()) {
            runCatching { syncItem(queuedItem) }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { error -> Log.e(TAG, "Immediate queued upload failed", error) }
        }
        true
    }

    override suspend fun refresh() {
        // Order matters: base refresh first creates any offline-created folder on
        // the server, then queued media can safely upload into that folder.
        delegate.refresh()
        if (hasNetwork()) syncQueuedUploadsOnce()
    }

    override suspend fun updateServerUrl(serverUrl: String) {
        queueMutex.withLock {
            check(queue.value.isEmpty() || ServerAddress.normalize(serverUrl) == delegate.observeServerUrl().first()) {
                "Finish or remove pending uploads before changing the server."
            }
            delegate.updateServerUrl(serverUrl)
        }
        if (hasNetwork()) syncQueuedUploadsOnce()
    }

    override suspend fun signInWithGoogle(idToken: String) = queueMutex.withLock {
        check(queue.value.isEmpty()) { "Finish or remove pending uploads before changing the account." }
        delegate.signInWithGoogle(idToken)
    }

    override suspend fun signOutFromGoogle() = queueMutex.withLock {
        check(queue.value.isEmpty()) { "Finish or remove pending uploads before signing out." }
        delegate.signOutFromGoogle()
    }

    override suspend fun deletePhoto(folderId: String, photoId: String) = queueMutex.withLock {
        val queueId = photoId.removePrefix(OFFLINE_ID_PREFIX)
        val queuedItem = queue.value.firstOrNull { it.id == queueId && it.folderId == folderId }
        if (queuedItem != null) {
            removeQueueItem(queuedItem, deleteStagedFile = true)
        } else {
            delegate.deletePhoto(folderId, photoId)
        }
    }

    suspend fun syncQueuedUploadsOnce() = queueMutex.withLock {
        if (!hasNetwork()) return@withLock
        val snapshot = queue.value.toList()
        snapshot.forEach { item ->
            runCatching { syncItem(item) }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { error -> Log.e(TAG, "Queued sync failed for ${item.title}", error) }
        }
    }

    private suspend fun syncItem(item: OfflineQueueItem): Boolean {
        if (!hasNetwork()) return false
        val uploadUri = Uri.parse(item.stagedUri)
        val sourceUri = Uri.parse(item.sourceUri)
        val acceptedByDelegate = delegate.uploadStagedMedia(
            folderId = item.folderId,
            uploadUri = uploadUri,
            sourceUri = sourceUri,
            displayName = item.title,
            mimeType = item.mimeType,
        )
        if (!acceptedByDelegate) {
            return false
        }

        // On success the delegate has a server-backed item. Remove only stale
        // unsynced local attempts for the same media; server originals are never
        // deleted by this cleanup.
        delegate.observeFolder(item.folderId).first()?.photos.orEmpty()
            .filter { photo ->
                photo.serverFileId == null &&
                    photo.id != item.photoId &&
                    item.matches(photo)
            }
            .forEach { stale -> delegate.deletePhoto(item.folderId, stale.id) }

        removeQueueItem(item, deleteStagedFile = true)
        return true
    }

    private fun createQueueItem(folderId: String, sourceUri: Uri): OfflineQueueItem {
        val id = UUID.randomUUID().toString()
        val title = resolveDisplayName(sourceUri) ?: "photo-${System.currentTimeMillis()}"
        val mimeType = appContext.contentResolver.getType(sourceUri)
            ?: guessMimeType(title)
            ?: "application/octet-stream"
        val durableUri = makeDurableUri(folderId, id, title, sourceUri)
        return OfflineQueueItem(
            id = id,
            folderId = folderId,
            title = title,
            mimeType = mimeType,
            sourceUri = sourceUri.toString(),
            stagedUri = durableUri.toString(),
        )
    }

    private fun makeDurableUri(folderId: String, id: String, title: String, sourceUri: Uri): Uri {
        // Always stage content in app-private storage. Some Android providers
        // accept takePersistableUriPermission() for a temporary Picker URI and
        // revoke it after process death or an update.

        val directory = File(appContext.filesDir, "offline_queue/$folderId")
        directory.mkdirs()
        val target = File(directory, "${id}_${safeFileName(title)}")
        try {
            appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
            } ?: error("Selected media could not be opened")
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        return Uri.fromFile(target)
    }

    private suspend fun removeQueueItem(item: OfflineQueueItem, deleteStagedFile: Boolean) {
        val nextQueue = queue.value.filterNot { it.id == item.id }
        persistQueue(nextQueue)
        queue.value = nextQueue
        if (deleteStagedFile) {
            val uri = Uri.parse(item.stagedUri)
            if (uri.scheme == "file") {
                runCatching { uri.path?.let(::File)?.delete() }
            }
        }
    }

    private fun hasNetwork(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        // A reachable LAN server does not require Android's internet validation.
        return manager.getNetworkCapabilities(network) != null
    }

    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return runCatching {
            appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun loadQueue(): List<OfflineQueueItem> {
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE, null)
            .orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        OfflineQueueItem(
                            id = item.getString("id"),
                            folderId = item.getString("folder_id"),
                            title = item.getString("title"),
                            mimeType = item.getString("mime_type"),
                            sourceUri = item.optString("source_uri")
                                .takeIf { it.isNotBlank() }
                                ?: item.getString("local_uri"),
                            stagedUri = item.optString("staged_uri")
                                .takeIf { it.isNotBlank() }
                                ?: item.getString("local_uri"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun persistQueue(items: List<OfflineQueueItem>) = withContext(Dispatchers.IO) {
        val payload = JSONArray()
        items.forEach { item ->
            payload.put(
                JSONObject()
                    .put("id", item.id)
                    .put("folder_id", item.folderId)
                    .put("title", item.title)
                    .put("mime_type", item.mimeType)
                    .put("source_uri", item.sourceUri)
                    .put("staged_uri", item.stagedUri),
            )
        }
        check(appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUEUE, payload.toString())
            .commit()) { "Could not save upload queue. Free some device storage and retry." }
    }

    private data class OfflineQueueItem(
        val id: String,
        val folderId: String,
        val title: String,
        val mimeType: String,
        val sourceUri: String,
        val stagedUri: String,
    ) {
        val photoId: String get() = OFFLINE_ID_PREFIX + id

        fun toPhotoItem(): PhotoItem = PhotoItem(
            id = photoId,
            title = title,
            status = PhotoSyncStatus.Pending,
            localUri = sourceUri,
            mimeType = mimeType,
        )

        fun matches(photo: PhotoItem): Boolean =
            photo.localUri == sourceUri ||
                photo.localUri == stagedUri ||
                (photo.title == title && photo.serverFileId == null)
    }

    companion object {
        private const val TAG = "PhotoSyncOffline"
        private const val PREFS = "photosync_offline_queue_v1"
        private const val KEY_QUEUE = "items"
        private const val OFFLINE_ID_PREFIX = "offline-"

        private val unsafeFileChars = Regex("""[\\/:*?\"<>|]""")

        private fun safeFileName(value: String): String =
            value.trim().replace(unsafeFileChars, "_").ifBlank { "media" }

        private fun guessMimeType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> null
        }
    }
}

internal fun Uri.isRevokedPhotoPickerUri(): Boolean =
    scheme == "content" &&
        (authority == "media" || authority == "com.android.providers.media.photopicker") &&
        toString().contains("picker_get_content")

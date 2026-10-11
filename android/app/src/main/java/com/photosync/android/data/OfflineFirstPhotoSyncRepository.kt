package com.photosync.android.data

import android.content.Context
import com.photosync.android.domain.model.MediaBatchProgress
import com.photosync.android.domain.model.MediaBatchPhase
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.photosync.android.domain.model.DashboardStats
import com.photosync.android.domain.model.FolderDetail
import com.photosync.android.domain.model.FolderSummary
import com.photosync.android.domain.model.PhotoCleanupPolicy
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
    private val networkAvailable: () -> Boolean = { AndroidSyncNetworkAccess(context).isAllowed() },
) : PhotoSyncRepository by delegate {
    private val appContext = context.applicationContext
    private val queueMutex = Mutex()
    private val queue = MutableStateFlow(loadQueue())
    private val batchProgress = MutableStateFlow(MediaBatchProgress())
    private val syncPreferences = PreferencesStore(appContext)
    private val mobileEnabled = MutableStateFlow(syncPreferences.getSyncNetworkPolicy() == SyncNetworkPolicy.AnyNetwork)

    override fun observeMobileSyncEnabled(): Flow<Boolean> = mobileEnabled.asStateFlow()
    override suspend fun updateMobileSyncEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            syncPreferences.updateSyncNetworkPolicy(if (enabled) SyncNetworkPolicy.AnyNetwork else SyncNetworkPolicy.WifiOnly)
        }
        mobileEnabled.value = enabled
        OfflineSyncScheduler.enqueue(appContext, replace = true)
        if (hasNetwork()) refresh()
    }

    override fun observeMediaBatchProgress(folderId: String): Flow<MediaBatchProgress> = combine(
        batchProgress, queue, mobileEnabled,
    ) { progress, items, mobile ->
        val pending = items.filter { it.folderId == folderId }
        val active = if (progress.folderId == folderId) progress else MediaBatchProgress(folderId, total = pending.size)
        active.copy(
            failed = maxOf(active.failed, pending.count { it.terminalFailure }),
            waitingForWifi = !mobile && pending.any { !it.terminalFailure } && !hasNetwork(),
        )
    }

    init {
        recoverInterruptedPreparation()
        if (queue.value.isNotEmpty()) {
            // Do not replace a worker that is constructing this application singleton.
            OfflineSyncScheduler.enqueue(appContext)
        }
    }

    override fun observeFolders(): Flow<List<FolderSummary>> = combine(
        delegate.observeFolders(),
        queue.asStateFlow(),
    ) { baseFolders, pending ->
        val queuedByFolder = pending.groupBy { it.folderId }
        baseFolders.map { folder ->
            val queued = queuedByFolder[folder.id].orEmpty()
            val pendingCount = queued.count { !it.terminalFailure }
            val failedCount = queued.count { it.terminalFailure }
            if (queued.isEmpty()) {
                folder
            } else {
                val duplicates = delegate.observeFolder(folder.id).first()?.photos.orEmpty().filter { photo ->
                    photo.serverFileId == null && queued.any { it.matches(photo) }
                }
                val duplicatePending = duplicates.count { it.status == PhotoSyncStatus.Pending || it.status == PhotoSyncStatus.Uploading }
                val duplicateFailed = duplicates.count { it.status == PhotoSyncStatus.Failed }
                folder.copy(
                    photoCount = folder.photoCount - duplicates.size + queued.size,
                    pendingCount = folder.pendingCount - duplicatePending + pendingCount,
                    failedCount = folder.failedCount - duplicateFailed + failedCount,
                    statusLabel = if (folder.failedCount - duplicateFailed + failedCount > 0) "Failed" else "Pending sync",
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

    override suspend fun uploadToFolder(folderId: String, uri: Uri): Boolean = enqueueMedia(folderId, listOf(uri))

    override suspend fun enqueueMedia(folderId: String, uris: List<Uri>): Boolean = enqueueBatch(folderId, uris, false)

    override suspend fun enqueueSharedMedia(folderId: String, uris: List<Uri>): Boolean = enqueueBatch(folderId, uris, true)

    // Atomic preparation: older queued work waits on the same mutex. The staging
    // journal is invisible to workers; one durable commit publishes the whole batch.
    private suspend fun enqueueBatch(folderId: String, uris: List<Uri>, shared: Boolean): Boolean = queueMutex.withLock {
        val folder = delegate.observeFolder(folderId).first() ?: return@withLock false
        if (!folder.canContribute || uris.isEmpty()) return@withLock false
        val uniqueUris = uris.distinct().filterNot { source ->
            queue.value.any { it.folderId == folderId && it.sourceUri == source.toString() }
        }
        if (uniqueUris.isEmpty()) return@withLock true
        val policy = effectiveCleanupPolicy(folderId)
        val batchId = UUID.randomUUID().toString()
        val staged = mutableListOf<OfflineQueueItem>()
        val directory = File(appContext.filesDir, "offline_queue/$folderId/$batchId")
        batchProgress.value = MediaBatchProgress(folderId, MediaBatchPhase.Preparing, total = uniqueUris.size)
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                check(appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("preparing", JSONObject().put("folder", folderId).put("directory", directory.absolutePath)
                        .put("total", uniqueUris.size).toString()).commit())
                uniqueUris.forEachIndexed { index, uri ->
                    currentCoroutineContext().ensureActive()
                    staged += createQueueItem(folderId, uri, policy,
                        shareBatchId = if (shared) batchId else null,
                        shareBatchSize = if (shared) uniqueUris.size else null,
                        stagingDirectory = directory)
                    batchProgress.value = batchProgress.value.copy(prepared = index + 1)
                }
            }
            // Do not allow cancellation between the disk commit and in-memory publication.
            withContext(NonCancellable + Dispatchers.IO) {
                val nextQueue = queue.value + staged
                persistQueue(nextQueue)
                queue.value = nextQueue
                committed = true
                OfflineSyncScheduler.enqueue(appContext)
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("preparing").remove("preparation_failure").commit()
            }
            batchProgress.value = batchProgress.value.copy(phase = MediaBatchPhase.Pending)
            if (shared) ShareUploadNotifier.showQueued(appContext, batchId, uniqueUris.size)
        } catch (error: Exception) {
            if (!committed) withContext(NonCancellable + Dispatchers.IO) {
                directory.deleteRecursively()
                batchProgress.value = batchProgress.value.copy(phase = MediaBatchPhase.Failed, failed = uniqueUris.size)
                // Retain a small failure marker for process restart / user-visible atomic rollback.
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("preparation_failure", JSONObject().put("folder", folderId).put("total", uniqueUris.size).toString())
                    .remove("preparing").commit()
            }
            if (error is CancellationException) throw error
            Log.e(TAG, "Atomic batch preparation failed", error)
            return@withLock committed
        }
        // Share Activity remains alive for source-deletion confirmation on allowed networks.
        // Normal picker also starts only AFTER the complete batch is durable.
        syncQueueLocked()
        true
    }

    private fun recoverInterruptedPreparation() {
        val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val preparing = preferences.getString("preparing", null)?.let(::JSONObject)
        if (preparing != null) {
            val directory = File(preparing.getString("directory"))
            val root = File(appContext.filesDir, "offline_queue").canonicalFile
            if (directory.canonicalPath.startsWith(root.path + File.separator) &&
                queue.value.none { Uri.parse(it.stagedUri).path?.startsWith(directory.path + File.separator) == true }) {
                directory.deleteRecursively()
                preferences.edit().putString("preparation_failure", preparing.toString()).commit()
            }
            preferences.edit().remove("preparing").commit()
        }
        preferences.getString("preparation_failure", null)?.let { raw ->
            val failure = JSONObject(raw)
            batchProgress.value = MediaBatchProgress(failure.getString("folder"), MediaBatchPhase.Failed,
                total = failure.getInt("total"), failed = failure.getInt("total"))
        }
    }

    override suspend fun onSyncNetworkChanged() {
        batchProgress.value = batchProgress.value.copy(waitingForWifi = !hasNetwork())
        if (hasNetwork()) refresh()
    }

    override suspend fun refresh() {
        // Order matters: base refresh first creates any offline-created folder on
        // the server, then queued media can safely upload into that folder.
        delegate.refresh()
        queueMutex.withLock { migrateLegacyItemsLocked() }
        if (hasNetwork()) syncQueuedUploadsOnce()
        else if (batchProgress.value.phase != MediaBatchPhase.Failed && batchProgress.value.phase != MediaBatchPhase.Preparing)
            batchProgress.value = batchProgress.value.copy(phase = MediaBatchPhase.Pending)
    }

    override suspend fun updateServerUrl(serverUrl: String) {
        queueMutex.withLock {
            check(queue.value.isEmpty() || ServerAddress.normalize(serverUrl) == delegate.observeServerUrl().first()) {
                "Finish or remove pending uploads before changing the server."
            }
            delegate.updateServerUrl(serverUrl)
            migrateLegacyItemsLocked()
        }
        if (hasNetwork()) syncQueuedUploadsOnce()
    }

    override suspend fun signInWithGoogle(idToken: String) {
        queueMutex.withLock {
            // A fresh installation commonly has uploads queued before the user
            // connects Google. Linking that anonymous device is safe: the server
            // attributes its existing albums/files to the verified account.
            // Keep blocking an account change while another account owns the queue.
            val currentAccount = delegate.observeGoogleAccount().first()
            check(queue.value.isEmpty() || currentAccount == null) {
                "Finish or remove pending uploads before changing the account."
            }
            delegate.signInWithGoogle(idToken)
            migrateLegacyItemsLocked()
        }
        if (hasNetwork()) syncQueuedUploadsOnce()
    }

    override suspend fun signOutFromGoogle() = queueMutex.withLock {
        check(queue.value.isEmpty()) { "Finish or remove pending uploads before signing out." }
        delegate.signOutFromGoogle()
    }

    override suspend fun deletePhoto(folderId: String, photoId: String) = queueMutex.withLock {
        val queueId = photoId.removePrefix(OFFLINE_ID_PREFIX)
        val queuedItem = queue.value.firstOrNull { it.id == queueId && it.folderId == folderId }
        if (queuedItem != null) {
            // Retire owned local attempts before dropping ownership, otherwise legacy
            // adoption could resurrect a queue item the user explicitly removed.
            delegate.observeFolder(folderId).first()?.photos.orEmpty()
                .filter { it.serverFileId == null && queuedItem.matches(it) }
                .forEach { delegate.deletePhoto(folderId, it.id) }
            removeQueueItem(queuedItem, deleteStagedFile = true)
        } else {
            delegate.deletePhoto(folderId, photoId)
        }
    }

    suspend fun syncQueuedUploadsOnce() = queueMutex.withLock { syncQueueLocked() }

    private fun hasPreparationFailure(): Boolean = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("preparation_failure", null)?.let { JSONObject(it).optString("folder") == batchProgress.value.folderId } == true

    private suspend fun syncQueueLocked() {
        if (queue.value.isEmpty()) return
        if (!hasNetwork()) {
            if (!hasPreparationFailure()) batchProgress.value = batchProgress.value.copy(phase = MediaBatchPhase.Pending)
            return
        }
        if (appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("retry_at", 0) > System.currentTimeMillis()) return
        val snapshot = queue.value.filterNot { it.terminalFailure || it.nextAttemptAt > System.currentTimeMillis() }
        for (item in snapshot) {
            if (!hasNetwork()) break
            val pending = queue.value.count { it.folderId == item.folderId }
            val previous = batchProgress.value
            batchProgress.value = if (previous.folderId == item.folderId && previous.total >= pending)
                previous.copy(phase = MediaBatchPhase.Synchronizing)
            else MediaBatchProgress(item.folderId, MediaBatchPhase.Synchronizing, total = pending, prepared = pending)
            try {
                check(syncItem(item)) { "Server did not accept media" }
                batchProgress.value = batchProgress.value.copy(completed = batchProgress.value.completed + 1)
            } catch (paused: SyncNetworkPausedException) {
                break
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                handleItemFailure(item, error)
            }
            if (appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong("retry_at", 0) > System.currentTimeMillis()) break
        }
        val remaining = queue.value.filter { it.folderId == batchProgress.value.folderId }
        batchProgress.value = batchProgress.value.copy(phase = when {
            hasPreparationFailure() -> MediaBatchPhase.Failed
            remaining.any { it.terminalFailure } -> MediaBatchPhase.Failed
            remaining.isNotEmpty() -> MediaBatchPhase.Pending
            batchProgress.value.total > 0 -> MediaBatchPhase.Completed
            else -> MediaBatchPhase.Pending
        })
    }

    // Legacy records are adopted, never sent by an independent retry engine.
    // Keep is intentional: old records did not capture an enqueue-time cleanup policy.
    private suspend fun migrateLegacyItemsLocked() {
        var migrated = false
        for (folder in delegate.observeFolders().first().filter { it.ownedByMe }) {
            val photos = delegate.observeFolder(folder.id).first()?.photos.orEmpty()
            for (photo in photos.filter { it.serverFileId == null && it.status in setOf(
                PhotoSyncStatus.Pending, PhotoSyncStatus.Uploading, PhotoSyncStatus.Failed) }) {
                if (queue.value.any { it.folderId == folder.id && it.matches(photo) }) continue
                val source = photo.uploadSourceUri ?: photo.localUri ?: continue
                val item = withContext(Dispatchers.IO) {
                    runCatching { createQueueItem(folder.id, Uri.parse(source), PhotoCleanupPolicy.Keep,
                        id = UUID.nameUUIDFromBytes("legacy:${folder.id}:${photo.id}".toByteArray()).toString())
                        .copy(sourceUri = photo.localUri ?: source,
                            terminalFailure = photo.failureCode in setOf("FILE_TOO_LARGE", "UNAUTHORIZED", "LOCAL_FILE_UNREADABLE"),
                            errorCode = photo.failureCode)
                    }.getOrElse {
                        OfflineQueueItem(UUID.randomUUID().toString(), folder.id, photo.title,
                            photo.mimeType ?: "application/octet-stream", PhotoCleanupPolicy.Keep, source, source,
                            terminalFailure = true, errorCode = "LOCAL_FILE_UNREADABLE")
                    }
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    val next = queue.value + item
                    persistQueue(next)
                    queue.value = next
                }
                migrated = true
                delegate.deletePhoto(folder.id, photo.id)
            }
        }
        if (migrated) OfflineSyncScheduler.enqueue(appContext)
    }

    private suspend fun handleItemFailure(item: OfflineQueueItem, error: Throwable) {
        if (error is SyncNetworkPausedException) return
        Log.e(TAG, "Queued sync failed for ${item.title}", error)
        // Do not retry deterministic 4xx failures forever. 429/5xx and network
        // failures remain queued; WorkManager applies persistent exponential backoff.
        val code = uploadFailureCode(error)
        if (error is PhotoSyncApiException && error.statusCode == 429) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("retry_at", System.currentTimeMillis() + maxOf(error.retryAfterMillis ?: 0,
                    30_000L shl item.retryAttempts.coerceAtMost(7))).commit()
        }
        val updated = queue.value.map {
            if (it.id == item.id) it.copy(
                terminalFailure = error is PhotoSyncApiException && !error.isTransient ||
                    error is LocalFileUnreadableException || error is java.io.FileNotFoundException || error is UploadProtocolException,
                errorCode = code,
                retryAttempts = it.retryAttempts + 1,
                nextAttemptAt = System.currentTimeMillis() + maxOf(
                    (error as? PhotoSyncApiException)?.retryAfterMillis ?: 0,
                    30_000L shl it.retryAttempts.coerceAtMost(7)),
            ) else it
        }
        persistQueue(updated)
        queue.value = updated
        notifyShareFailure(item)
    }

    private suspend fun syncItem(item: OfflineQueueItem): Boolean {
        if (!hasNetwork()) throw SyncNetworkPausedException()
        if (item.nextAttemptAt > System.currentTimeMillis() ||
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("retry_at", 0) > System.currentTimeMillis()) return false
        // A server-backed local record survives a crash between completion and queue removal.
        if (delegate.observeFolder(item.folderId).first()?.photos.orEmpty().any {
            it.serverFileId != null && it.status == PhotoSyncStatus.Synced &&
                it.uploadSourceUri == item.stagedUri
        }) {
            removeQueueItem(item, deleteStagedFile = true)
            return true
        }
        val uploadUri = Uri.parse(item.stagedUri)
        val sourceUri = Uri.parse(item.sourceUri)
        val acceptedByDelegate = withContext(UploadTransferContext.current.asContextElement(UploadTransferContext(item.id, item.retryAttempts))) { delegate.uploadStagedMedia(
            folderId = item.folderId,
            uploadUri = uploadUri,
            sourceUri = sourceUri,
            displayName = item.title,
            mimeType = item.mimeType,
            cleanupPolicy = item.cleanupPolicy,
        ) }
        if (!acceptedByDelegate) {
            return false
        }

        removeQueueItem(item, deleteStagedFile = true)

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

        item.shareBatchId?.let { batchId ->
            if (queue.value.none { it.shareBatchId == batchId }) {
                ShareUploadNotifier.showComplete(appContext, batchId, item.shareBatchSize ?: 1)
            }
        }
        return true
    }

    private fun createQueueItem(
        folderId: String,
        sourceUri: Uri,
        cleanupPolicy: PhotoCleanupPolicy,
        shareBatchId: String? = null,
        shareBatchSize: Int? = null,
        stagingDirectory: File? = null,
        id: String = UUID.randomUUID().toString(),
    ): OfflineQueueItem {
        val title = resolveDisplayName(sourceUri) ?: "photo-${System.currentTimeMillis()}"
        val mimeType = appContext.contentResolver.getType(sourceUri)
            ?: guessMimeType(title)
            ?: "application/octet-stream"
        val durableUri = makeDurableUri(folderId, id, title, sourceUri, stagingDirectory)
        return OfflineQueueItem(
            id = id,
            folderId = folderId,
            title = title,
            mimeType = mimeType,
            cleanupPolicy = cleanupPolicy,
            sourceUri = sourceUri.toString(),
            stagedUri = durableUri.toString(),
            shareBatchId = shareBatchId,
            shareBatchSize = shareBatchSize,
        )
    }

    private fun makeDurableUri(folderId: String, id: String, title: String, sourceUri: Uri, stagingDirectory: File? = null): Uri {
        // Always stage content in app-private storage. Some Android providers
        // accept takePersistableUriPermission() for a temporary Picker URI and
        // revoke it after process death or an update.

        val directory = stagingDirectory ?: File(appContext.filesDir, "offline_queue/$folderId")
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
        if (deleteStagedFile) deleteStagedFile(item)
    }

    private suspend fun effectiveCleanupPolicy(folderId: String): PhotoCleanupPolicy =
        delegate.observeFolderPhotoCleanupPolicy(folderId).first()
            ?: delegate.observeGlobalPhotoCleanupPolicy().first()

    private fun deleteStagedFile(item: OfflineQueueItem) {
        val uri = Uri.parse(item.stagedUri)
        if (uri.scheme == "file") runCatching {
            val file = uri.path?.let(::File) ?: return@runCatching
            val root = File(appContext.filesDir, "offline_queue").canonicalPath + File.separator
            if (file.canonicalPath.startsWith(root)) file.delete()
        }
    }

    private suspend fun notifyShareFailure(item: OfflineQueueItem) {
        val batchId = item.shareBatchId ?: return
        if (queue.value.filter { it.shareBatchId == batchId }.any { it.failureNotified }) return
        ShareUploadNotifier.showFailed(appContext, batchId)
        val updated = queue.value.map { queued ->
            if (queued.shareBatchId == batchId) queued.copy(failureNotified = true) else queued
        }
        persistQueue(updated)
        queue.value = updated
    }

    // A reachable LAN server does not require Android's internet validation.
    private fun hasNetwork(): Boolean = networkAvailable()

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
                            // Queue entries from before this field was added must never be
                            // retroactively cleaned up after the setting changes.
                            cleanupPolicy = item.optString("cleanup_policy")
                                .let { value -> runCatching { PhotoCleanupPolicy.valueOf(value) }.getOrNull() }
                                ?: PhotoCleanupPolicy.Keep,
                            sourceUri = item.optString("source_uri")
                                .takeIf { it.isNotBlank() }
                                ?: item.getString("local_uri"),
                            stagedUri = item.optString("staged_uri")
                                .takeIf { it.isNotBlank() }
                                ?: item.getString("local_uri"),
                            shareBatchId = item.optString("share_batch_id").takeIf { it.isNotBlank() },
                            shareBatchSize = item.optInt("share_batch_size").takeIf { it > 0 },
                            failureNotified = item.optBoolean("failure_notified", false),
                            terminalFailure = item.optBoolean("terminal_failure", false),
                            errorCode = item.optString("error_code").takeIf { it.isNotBlank() },
                            retryAttempts = item.optInt("retry_attempts", 0),
                            nextAttemptAt = item.optLong("next_attempt_at", 0),
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
                    .put("cleanup_policy", item.cleanupPolicy.name)
                    .put("source_uri", item.sourceUri)
                    .put("staged_uri", item.stagedUri)
                    .put("share_batch_id", item.shareBatchId ?: JSONObject.NULL)
                    .put("share_batch_size", item.shareBatchSize ?: JSONObject.NULL)
                    .put("failure_notified", item.failureNotified)
                    .put("terminal_failure", item.terminalFailure)
                    .put("error_code", item.errorCode ?: JSONObject.NULL)
                    .put("retry_attempts", item.retryAttempts)
                    .put("next_attempt_at", item.nextAttemptAt),
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
        val cleanupPolicy: PhotoCleanupPolicy,
        val sourceUri: String,
        val stagedUri: String,
        val shareBatchId: String? = null,
        val shareBatchSize: Int? = null,
        val failureNotified: Boolean = false,
        val terminalFailure: Boolean = false,
        val errorCode: String? = null,
        val retryAttempts: Int = 0,
        val nextAttemptAt: Long = 0,
    ) {
        val photoId: String get() = OFFLINE_ID_PREFIX + id

        fun toPhotoItem(): PhotoItem = PhotoItem(
            id = photoId,
            title = title,
            status = if (terminalFailure) PhotoSyncStatus.Failed else PhotoSyncStatus.Pending,
            localUri = sourceUri,
            mimeType = mimeType,
            failureCode = errorCode,
        )

        fun matches(photo: PhotoItem): Boolean =
            photo.uploadSourceUri == stagedUri ||
            photo.localUri == sourceUri ||
                photo.localUri == stagedUri
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

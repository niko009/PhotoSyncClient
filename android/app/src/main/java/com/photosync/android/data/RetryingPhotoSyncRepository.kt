package com.photosync.android.data

import android.net.Uri
import android.util.Log
import com.photosync.android.domain.model.PhotoSyncStatus
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Adds a lightweight retry queue on top of the network repository.
 *
 * The underlying repository remains the source of truth. This wrapper retries
 * locally retained Failed/Pending/stale Uploading media after connectivity is
 * restored. A successful retry removes only the stale local queue item; the
 * server original is never deleted. Server uploads are SHA-256 deduplicated, so
 * retrying after an uncertain network response does not create a second original.
 */
class RetryingPhotoSyncRepository(
    private val delegate: PhotoSyncRepository,
) : PhotoSyncRepository by delegate {
    private val retryMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            delay(1_500)
            runCatching { refresh() }
                .onFailure { error -> Log.e(TAG, "Initial retry refresh failed", error) }
        }
    }

    override suspend fun refresh() {
        delegate.refresh()
        retryQueuedUploadsOnce()
    }

    override suspend fun updateServerUrl(serverUrl: String) {
        delegate.updateServerUrl(serverUrl)
        retryQueuedUploadsOnce()
    }

    private suspend fun retryQueuedUploadsOnce() = retryMutex.withLock {
        val folders = delegate.observeFolders().first()
            .filter { it.ownedByMe }

        folders.forEach folderLoop@{ summary ->
            val detail = delegate.observeFolder(summary.id).first() ?: return@folderLoop

            detail.photos
                .filter { photo ->
                    photo.serverFileId == null &&
                        !photo.localUri.isNullOrBlank() &&
                        photo.status in RETRYABLE_STATUSES
                }
                .forEach photoLoop@{ photo ->
                    val localUri = photo.localUri ?: return@photoLoop
                    val uri = runCatching { Uri.parse(localUri) }.getOrNull()
                        ?: return@photoLoop
                    val uploaded = runCatching {
                        delegate.uploadToFolder(summary.id, uri)
                    }.onFailure { error ->
                        Log.e(TAG, "Retry failed for ${photo.title}", error)
                    }.getOrDefault(false)

                    if (uploaded) {
                        // deletePhoto is local-only in PhotoSync. It does not delete
                        // the newly committed server original.
                        delegate.deletePhoto(summary.id, photo.id)
                    }
                }
        }
    }

    companion object {
        private const val TAG = "PhotoSyncRetry"
        private val RETRYABLE_STATUSES = setOf(
            PhotoSyncStatus.Pending,
            PhotoSyncStatus.Failed,
            PhotoSyncStatus.Uploading,
        )
    }
}

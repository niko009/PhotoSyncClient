package com.photosync.android.data

import android.net.Uri
import android.util.Log
import com.photosync.android.domain.model.ConnectionStatus
import com.photosync.android.domain.model.PhotoSyncStatus
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Compatibility retry for media that was already stored as Failed/Pending by
 * older PhotoSync builds before the durable offline queue existed.
 */
class RetryingPhotoSyncRepository(
    private val delegate: PhotoSyncRepository,
) : PhotoSyncRepository by delegate {
    private val retryMutex = Mutex()

    override suspend fun refresh() {
        delegate.refresh()
        if (delegate.observeStats().first().connectionStatus == ConnectionStatus.Online) {
            retryQueuedUploadsOnce()
        }
    }

    override suspend fun updateServerUrl(serverUrl: String) {
        delegate.updateServerUrl(serverUrl)
        if (delegate.observeStats().first().connectionStatus == ConnectionStatus.Online) {
            retryQueuedUploadsOnce()
        }
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
                        if (error is CancellationException) throw error
                        Log.e(TAG, "Legacy retry failed for ${photo.title}", error)
                    }.getOrDefault(false)

                    if (uploaded) {
                        // Local queue cleanup only; server originals are preserved.
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

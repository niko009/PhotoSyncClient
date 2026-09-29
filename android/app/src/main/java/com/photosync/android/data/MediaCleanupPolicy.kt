package com.photosync.android.data

import com.photosync.android.domain.model.PhotoCleanupPolicy

internal enum class MediaCleanupAction {
    KeepSource,
    CompressImageThenDeleteSource,
    DeleteSource,
}

internal fun cleanupAction(policy: PhotoCleanupPolicy, mimeType: String): MediaCleanupAction = when (policy) {
    PhotoCleanupPolicy.Keep -> MediaCleanupAction.KeepSource
    PhotoCleanupPolicy.Compress -> if (mimeType.startsWith("image/")) {
        MediaCleanupAction.CompressImageThenDeleteSource
    } else {
        MediaCleanupAction.KeepSource
    }
    PhotoCleanupPolicy.Delete -> MediaCleanupAction.DeleteSource
}

internal fun cleanupActionAfterUpload(
    uploadSucceeded: Boolean,
    policy: PhotoCleanupPolicy,
    mimeType: String,
): MediaCleanupAction = if (uploadSucceeded) {
    cleanupAction(policy, mimeType)
} else {
    MediaCleanupAction.KeepSource
}

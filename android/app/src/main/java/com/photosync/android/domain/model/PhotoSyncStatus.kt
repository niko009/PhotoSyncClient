package com.photosync.android.domain.model

enum class PhotoSyncStatus {
    Synced,
    Pending,
    Uploading,
    RemoteOnly,
    Failed,
}

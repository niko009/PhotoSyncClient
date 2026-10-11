package com.photosync.android.domain.model

enum class MediaBatchPhase { Pending, Preparing, Synchronizing, Failed, Completed }
data class MediaBatchProgress(
    val folderId: String = "",
    val phase: MediaBatchPhase = MediaBatchPhase.Pending,
    val prepared: Int = 0,
    val total: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val waitingForWifi: Boolean = false,
)

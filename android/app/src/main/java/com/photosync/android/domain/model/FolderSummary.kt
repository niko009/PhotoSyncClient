package com.photosync.android.domain.model

data class FolderSummary(
    val id: String,
    val name: String,
    val photoCount: Int,
    val syncedCount: Int,
    val pendingCount: Int,
    val failedCount: Int,
    val statusLabel: String,
    val previewThumbnailPaths: List<String> = emptyList(),
)

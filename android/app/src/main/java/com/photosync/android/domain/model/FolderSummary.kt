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
    val remoteAlbumId: Int? = null,
    val permission: String = "Owner",
    val sharingMode: String = "Private",
    val ownedByMe: Boolean = true,
) {
    val canContribute: Boolean
        get() = ownedByMe || permission.equals("Contribute", ignoreCase = true) || permission.equals("Owner", ignoreCase = true)
}

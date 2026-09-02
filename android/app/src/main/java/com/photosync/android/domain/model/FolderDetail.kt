package com.photosync.android.domain.model

data class FolderDetail(
    val id: String,
    val name: String,
    val photos: List<PhotoItem>,
    val remoteAlbumId: Int? = null,
    val permission: String = "Owner",
    val sharingMode: String = "Private",
    val ownedByMe: Boolean = true,
) {
    val canContribute: Boolean
        get() = ownedByMe || permission.equals("Contribute", ignoreCase = true) || permission.equals("Owner", ignoreCase = true)
}

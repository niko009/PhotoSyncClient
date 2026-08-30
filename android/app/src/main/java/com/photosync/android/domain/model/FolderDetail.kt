package com.photosync.android.domain.model

data class FolderDetail(
    val id: String,
    val name: String,
    val photos: List<PhotoItem>,
)

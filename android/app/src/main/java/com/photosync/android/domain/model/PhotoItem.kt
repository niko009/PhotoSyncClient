package com.photosync.android.domain.model

import android.net.Uri

data class PhotoItem(
    val id: String,
    val title: String,
    val status: PhotoSyncStatus,
    val localUri: String? = null,
    val thumbnailPath: String? = null,
    val serverFileId: Int? = null,
    val serverRelativePath: String? = null,
    val mimeType: String? = null,
)

fun PhotoItem.toUriOrNull(): Uri? = localUri?.let(Uri::parse)

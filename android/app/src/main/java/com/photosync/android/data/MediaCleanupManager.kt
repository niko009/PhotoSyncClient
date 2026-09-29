package com.photosync.android.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/**
 * Persists user-approved gallery cleanup that still needs an Android system
 * confirmation. Upload code can run in the background; the actual confirmation
 * is launched only while the app UI is visible.
 */
class MediaCleanupManager(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val pending = MutableStateFlow(loadPending())

    val pendingDeletionUris: StateFlow<List<Uri>> = pending.asStateFlow()

    fun requestDelete(uri: Uri) {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).delete() }
            return
        }
        val deleteUri = resolveMediaStoreDeleteUri(appContext, uri) ?: return
        if (deleteUri in pending.value) return
        pending.value = pending.value + deleteUri
        persist()
    }

    fun complete(uri: Uri) {
        pending.value = pending.value.filterNot { it == uri }
        persist()
    }

    private fun loadPending(): List<Uri> {
        val raw = preferences.getString(KEY_PENDING, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    resolveMediaStoreDeleteUri(appContext, Uri.parse(array.getString(index)))?.let(::add)
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val array = JSONArray()
        pending.value.forEach { array.put(it.toString()) }
        preferences.edit().putString(KEY_PENDING, array.toString()).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "photosync_media_cleanup_v1"
        private const val KEY_PENDING = "pending_deletions"
    }
}

private const val LOCAL_PHOTO_PICKER_AUTHORITY = "com.android.providers.media.photopicker"

/** Returns the concrete MediaStore item that Android's delete consent API accepts. */
internal fun resolveMediaStoreDeleteUri(context: Context, sourceUri: Uri): Uri? {
    if (sourceUri.scheme != "content") return null

    if (sourceUri.authority == MediaStore.AUTHORITY) {
        val segments = sourceUri.pathSegments
        if (segments.firstOrNull() in setOf("picker", "picker_get_content")) {
            return localPickerMediaId(segments)?.let(::externalMediaFileUri)
        }
        if (segments.lastOrNull()?.toLongOrNull() != null) return sourceUri
    }

    if (sourceUri.authority == "com.android.providers.media.documents") {
        mediaDocumentUri(sourceUri)?.let { return it }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        DocumentsContract.isDocumentUri(context, sourceUri)
    ) {
        return runCatching { MediaStore.getMediaUri(context, sourceUri) }
            .getOrNull()
            ?.takeIf { it.lastPathSegment?.toLongOrNull() != null }
    }

    return null
}

private fun localPickerMediaId(segments: List<String>): Long? = when {
    // Android 11/12 Photo Picker format: /picker/<user-id>/<media-id>
    segments.size == 3 && segments[1].toIntOrNull() != null -> segments[2].toLongOrNull()
    // Current local Photo Picker format:
    // /picker/<user-id>/com.android.providers.media.photopicker/media/<media-id>
    segments.size == 5 &&
        segments[1].toIntOrNull() != null &&
        segments[2] == LOCAL_PHOTO_PICKER_AUTHORITY &&
        segments[3] == "media" -> segments[4].toLongOrNull()
    else -> null
}

private fun mediaDocumentUri(uri: Uri): Uri? {
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
    val (type, id) = documentId.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
    val mediaId = id.toLongOrNull() ?: return null
    val collection = when (type) {
        "image" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else -> return null
    }
    return ContentUris.withAppendedId(collection, mediaId)
}

private fun externalMediaFileUri(id: Long): Uri =
    ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id)

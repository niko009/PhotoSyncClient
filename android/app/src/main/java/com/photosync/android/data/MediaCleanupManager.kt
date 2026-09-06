package com.photosync.android.data

import android.content.Context
import android.net.Uri
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
        if (uri.scheme != "content" || uri in pending.value) return
        pending.value = pending.value + uri
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
                    Uri.parse(array.getString(index)).takeIf { it.scheme == "content" }?.let(::add)
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

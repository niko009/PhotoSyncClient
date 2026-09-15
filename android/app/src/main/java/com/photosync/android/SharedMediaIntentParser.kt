package com.photosync.android

import android.content.Intent
import android.net.Uri
import android.os.Build

internal object SharedMediaIntentParser {
    fun parse(intent: Intent?): List<Uri> {
        if (intent == null || intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            return emptyList()
        }
        val mimeType = intent.type.orEmpty()
        if (mimeType.isNotEmpty() &&
            !mimeType.startsWith("image/") &&
            !mimeType.startsWith("video/") &&
            mimeType != "*/*"
        ) {
            return emptyList()
        }

        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> streamUri(intent)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> uris += streamUris(intent)
        }
        intent.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(uris::add)
            }
        }
        return uris.distinct()
    }

    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        }

    private fun streamUris(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
}

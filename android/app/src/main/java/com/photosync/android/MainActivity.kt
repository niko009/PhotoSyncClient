package com.photosync.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.photosync.android.ui.PhotoSyncApp
import com.photosync.android.ui.theme.PhotoSyncTheme

class MainActivity : ComponentActivity() {
    private val pendingInviteToken = mutableStateOf<String?>(null)
    private val pendingSharedMedia = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        val appContainer = (application as PhotoSyncApplication).container
        setContent {
            PhotoSyncTheme {
                PhotoSyncApp(
                    repository = appContainer.photoSyncRepository,
                    familyApi = appContainer.familyApiClient,
                    pendingInviteToken = pendingInviteToken.value,
                    onInviteHandled = { pendingInviteToken.value = null },
                    pendingSharedMedia = pendingSharedMedia.value,
                    onSharedMediaHandled = ::clearSharedMedia,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        pendingInviteToken.value = inviteToken(intent?.data)
        val shared = sharedMedia(intent)
        if (shared.isNotEmpty()) {
            pendingSharedMedia.value = shared
        }
    }

    private fun clearSharedMedia() {
        pendingSharedMedia.value = emptyList()
        // Do not re-import the same share payload after configuration changes.
        setIntent(Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN })
    }

    private fun inviteToken(uri: Uri?): String? {
        if (uri == null) return null
        val token = when {
            uri.scheme == "photosync" && uri.host == "join" -> uri.pathSegments.firstOrNull()
            (uri.scheme == "https" || uri.scheme == "http") && uri.pathSegments.firstOrNull() == "join" -> uri.pathSegments.getOrNull(1)
            else -> null
        }
        return token?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{20,256}")) }
    }

    private fun sharedMedia(intent: Intent?): List<Uri> {
        if (intent == null || intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            return emptyList()
        }
        val mimeType = intent.type.orEmpty()
        if (mimeType.isNotEmpty() &&
            !mimeType.startsWith("image/") &&
            !mimeType.startsWith("video/") &&
            mimeType != "*/*") {
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

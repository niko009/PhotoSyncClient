package com.photosync.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.photosync.android.ui.PhotoSyncApp
import com.photosync.android.ui.theme.PhotoSyncTheme

class MainActivity : ComponentActivity() {
    private val pendingInviteToken = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingInviteToken.value = inviteToken(intent?.data)

        val appContainer = (application as PhotoSyncApplication).container
        setContent {
            PhotoSyncTheme {
                PhotoSyncApp(
                    repository = appContainer.photoSyncRepository,
                    familyApi = appContainer.familyApiClient,
                    pendingInviteToken = pendingInviteToken.value,
                    onInviteHandled = { pendingInviteToken.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingInviteToken.value = inviteToken(intent.data)
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
}

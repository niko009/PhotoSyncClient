package com.photosync.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photosync.android.ui.MediaDeletionEffect
import com.photosync.android.ui.share.ShareImportScreen
import com.photosync.android.ui.share.ShareImportViewModel
import com.photosync.android.ui.theme.PhotoSyncTheme

/** A short-lived share target that returns to the source gallery after durable queueing. */
class ShareReceiverActivity : ComponentActivity() {
    private var submitAfterPermission: (() -> Unit)? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        submitAfterPermission?.invoke()
        submitAfterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedMedia = SharedMediaIntentParser.parse(intent)
        if (sharedMedia.isEmpty()) {
            finish()
            return
        }

        val container = (application as PhotoSyncApplication).container
        val repository = container.photoSyncRepository
        setContent {
            PhotoSyncTheme {
                val shareViewModel: ShareImportViewModel = viewModel(
                    factory = ShareImportViewModel.Factory(repository),
                )
                val shareState by shareViewModel.state.collectAsStateWithLifecycle()
                val pendingDeletionUris by container.mediaCleanupManager.pendingDeletionUris
                    .collectAsStateWithLifecycle()
                ShareImportScreen(
                    viewModel = shareViewModel,
                    sharedMedia = sharedMedia,
                    onSubmit = {
                        runAfterNotificationPermission(shareViewModel::enqueueToSelectedFolder)
                    },
                    onCancel = ::finish,
                    onQueued = {},
                )
                MediaDeletionEffect(container.mediaCleanupManager)
                LaunchedEffect(shareState.isQueued, pendingDeletionUris) {
                    if (shareState.isQueued && pendingDeletionUris.isEmpty()) finish()
                }
            }
        }
    }

    private fun runAfterNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            submitAfterPermission = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
}

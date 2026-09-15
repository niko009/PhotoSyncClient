package com.photosync.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
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

        val repository = (application as PhotoSyncApplication).container.photoSyncRepository
        setContent {
            PhotoSyncTheme {
                val shareViewModel: ShareImportViewModel = viewModel(
                    factory = ShareImportViewModel.Factory(repository),
                )
                ShareImportScreen(
                    viewModel = shareViewModel,
                    sharedMedia = sharedMedia,
                    onSubmit = {
                        runAfterNotificationPermission(shareViewModel::enqueueToSelectedFolder)
                    },
                    onCancel = ::finish,
                    onQueued = ::finish,
                )
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

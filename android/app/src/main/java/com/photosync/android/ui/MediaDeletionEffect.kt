package com.photosync.android.ui

import android.app.Activity
import android.app.RecoverableSecurityException
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photosync.android.R
import com.photosync.android.data.MediaCleanupManager

/** Launches Android's required consent dialog only after server upload succeeds. */
@Composable
fun MediaDeletionEffect(manager: MediaCleanupManager) {
    val context = LocalContext.current
    val pending by manager.pendingDeletionUris.collectAsStateWithLifecycle()
    var activeUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val deletedMessage = stringResource(R.string.cleanup_delete_complete)
    val keptMessage = stringResource(R.string.cleanup_delete_cancelled)
    val failedMessage = stringResource(R.string.cleanup_delete_failed)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        activeUri?.let(manager::complete)
        Toast.makeText(
            context,
            if (result.resultCode == Activity.RESULT_OK) deletedMessage else keptMessage,
            Toast.LENGTH_LONG,
        ).show()
        activeUri = null
    }

    LaunchedEffect(pending.firstOrNull()) {
        val uri = pending.firstOrNull() ?: return@LaunchedEffect
        activeUri = uri
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
                }
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                    try {
                        context.contentResolver.delete(uri, null, null)
                        manager.complete(uri)
                        activeUri = null
                        Toast.makeText(context, deletedMessage, Toast.LENGTH_LONG).show()
                        return@LaunchedEffect
                    } catch (error: RecoverableSecurityException) {
                        error.userAction.actionIntent.intentSender
                    }
                }
                else -> {
                    check(context.contentResolver.delete(uri, null, null) > 0) {
                        "Media provider did not delete the selected item"
                    }
                    manager.complete(uri)
                    activeUri = null
                    Toast.makeText(context, deletedMessage, Toast.LENGTH_LONG).show()
                    return@LaunchedEffect
                }
            }
        }.onSuccess { intentSender ->
            launcher.launch(IntentSenderRequest.Builder(intentSender).build())
        }.onFailure {
            manager.complete(uri)
            activeUri = null
            Toast.makeText(context, failedMessage, Toast.LENGTH_LONG).show()
        }
    }
}

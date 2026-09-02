package com.photosync.android.update

import android.app.DownloadManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.photosync.android.R
import kotlinx.coroutines.delay

@Composable
fun AppUpdatePrompt() {
    val context = LocalContext.current
    val manager = remember(context) { AppUpdateManager(context) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var downloadedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        checking = true
        manager.checkForUpdate()
            .onSuccess { updateInfo = it }
            .onFailure { failed = true }
        checking = false
    }

    LaunchedEffect(downloadId, updateInfo) {
        val id = downloadId ?: return@LaunchedEffect
        val info = updateInfo ?: return@LaunchedEffect
        while (true) {
            when (manager.queryDownload(id)?.state) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    manager.verifyDownloadedApk(id, info)
                        .onSuccess { downloadedUri = it }
                        .onFailure { failed = true }
                    return@LaunchedEffect
                }
                DownloadManager.STATUS_FAILED -> {
                    failed = true
                    return@LaunchedEffect
                }
            }
            delay(750)
        }
    }

    val info = updateInfo
    if (!dismissed && info != null) {
        AlertDialog(
            onDismissRequest = { if (downloadId == null) dismissed = true },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                val text = when {
                    failed -> stringResource(R.string.update_failed)
                    downloadedUri != null -> stringResource(R.string.update_ready, info.versionName)
                    downloadId != null -> stringResource(R.string.update_downloading, info.versionName)
                    else -> stringResource(R.string.update_available_body, info.versionName)
                }
                Text(text)
            },
            confirmButton = {
                when {
                    downloadedUri != null -> TextButton(onClick = {
                        manager.requestInstall(downloadedUri!!)
                    }) { Text(stringResource(R.string.install_update)) }
                    downloadId != null && !failed -> CircularProgressIndicator()
                    else -> TextButton(onClick = {
                        failed = false
                        downloadedUri = null
                        downloadId = manager.enqueueDownload(info)
                    }) { Text(stringResource(R.string.update_now)) }
                }
            },
            dismissButton = {
                if (downloadId == null || failed) {
                    TextButton(onClick = { dismissed = true }) {
                        Text(stringResource(R.string.update_later))
                    }
                }
            },
        )
    }
}

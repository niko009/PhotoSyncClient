package com.photosync.android.ui.components

import com.photosync.android.data.decodePhotoBitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photosync.android.R
import com.photosync.android.domain.model.DashboardStats
import com.photosync.android.domain.model.ConnectionStatus
import com.photosync.android.domain.model.PhotoSyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AlbumPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun AlbumHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
        Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleLarge)
        trailing()
    }
}

@Composable
fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource(R.drawable.ic_album_brand), null, Modifier.size(38.dp))
        Text("PhotoSync", style = MaterialTheme.typography.titleLarge)
    }
}

/** Decode bounded images off the UI thread, not full camera bitmaps in a grid. */
@Composable
fun AlbumImage(path: String?, description: String?, modifier: Modifier = Modifier, fit: Boolean = false) {
    val context = LocalContext.current
    val image by produceState<android.graphics.Bitmap?>(null, path, fit) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (path.isNullOrBlank()) return@runCatching null
                fun stream() = if (path.startsWith("content:") || path.startsWith("file:"))
                    context.contentResolver.openInputStream(Uri.parse(path)) else File(path).inputStream()
                stream()?.use { decodePhotoBitmap(it, if (fit) 1600 else 480) }
            }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (image != null) Image(image!!.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = if (fit) ContentScale.Fit else ContentScale.Crop)
        else Image(painterResource(R.drawable.ic_album_brand), description, Modifier.size(42.dp))
    }
}

@Composable
fun PhotoSyncStatus.statusText(): String = stringResource(when (this) {
    PhotoSyncStatus.Synced -> R.string.synced
    PhotoSyncStatus.RemoteOnly -> R.string.remote_only
    PhotoSyncStatus.Uploading -> R.string.uploading
    PhotoSyncStatus.Failed -> R.string.failed
    PhotoSyncStatus.Pending -> R.string.pending
})

@Composable
fun SyncSummary(stats: DashboardStats, onRefresh: () -> Unit, expanded: Boolean = false) {
    val online = stats.connectionStatus == ConnectionStatus.Online
    val text = when {
        stats.connectionStatus == ConnectionStatus.Offline -> R.string.album_offline
        stats.connectionStatus == ConnectionStatus.Connecting -> R.string.album_connecting
        !online -> R.string.album_not_checked
        stats.failedPhotos > 0 -> R.string.album_needs_attention
        stats.pendingPhotos > 0 -> R.string.uploading
        stats.totalPhotos == 0 -> R.string.album_ready
        else -> R.string.album_saved
    }
    Surface(shape = MaterialTheme.shapes.small,
        color = if (online && stats.failedPhotos == 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (online && stats.failedPhotos == 0) Icons.Default.Check else Icons.Default.Info, null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(text), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.album_this_phone), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRefresh, enabled = stats.connectionStatus != ConnectionStatus.Connecting) { Text(stringResource(R.string.refresh)) }
            }
            if (expanded) {
                Text(stringResource(R.string.photos_progress, stats.syncedPhotos, stats.totalPhotos), style = MaterialTheme.typography.headlineMedium)
                LinearProgressIndicator(progress = if (stats.totalPhotos == 0) 0f else stats.syncedPhotos.toFloat() / stats.totalPhotos, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.album_sync_manual), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

package com.photosync.android.ui.folder

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photosync.android.R
import com.photosync.android.domain.model.FolderDetail
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.model.PhotoItem
import com.photosync.android.domain.model.PhotoSyncStatus
import com.photosync.android.ui.settings.label
import com.photosync.android.ui.theme.PhotoSyncTheme

private val ScreenBackground = Color(0xFF07111C)
private val Panel = Color(0xFF172231)
private val PanelStroke = Color(0xFF2A3548)
private val Accent = Color(0xFF6270FF)
private val Success = Color(0xFF45D074)
private val Warning = Color(0xFFFFB02E)
private val Danger = Color(0xFFFF5757)
private val MutedText = Color(0xFFA6ADBA)

@Composable
fun FolderDetailScreen(
    state: kotlinx.coroutines.flow.StateFlow<FolderDetailUiState>,
    onBack: () -> Unit,
    onAddMedia: () -> Unit,
    onDeletePhoto: (String) -> Unit,
    onDownloadPhoto: (String) -> Unit,
    onUpdateCleanupPolicy: (PhotoCleanupPolicy?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState = state.collectAsStateWithLifecycle()
    FolderDetailScreen(
        state = uiState,
        onBack = onBack,
        onAddMedia = onAddMedia,
        onDeletePhoto = onDeletePhoto,
        onDownloadPhoto = onDownloadPhoto,
        onUpdateCleanupPolicy = onUpdateCleanupPolicy,
        modifier = modifier,
    )
}

@Composable
fun FolderDetailScreen(
    state: State<FolderDetailUiState>,
    onBack: () -> Unit,
    onAddMedia: () -> Unit,
    onDeletePhoto: (String) -> Unit,
    onDownloadPhoto: (String) -> Unit,
    onUpdateCleanupPolicy: (PhotoCleanupPolicy?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FolderDetailScreen(
        state = state.value,
        onBack = onBack,
        onAddMedia = onAddMedia,
        onDeletePhoto = onDeletePhoto,
        onDownloadPhoto = onDownloadPhoto,
        onUpdateCleanupPolicy = onUpdateCleanupPolicy,
        modifier = modifier,
    )
}

@Composable
fun FolderDetailScreen(
    state: FolderDetailUiState,
    onBack: () -> Unit,
    onAddMedia: () -> Unit,
    onDeletePhoto: (String) -> Unit,
    onDownloadPhoto: (String) -> Unit,
    onUpdateCleanupPolicy: (PhotoCleanupPolicy?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPolicyDialogVisible by remember { mutableStateOf(false) }
    var policyDraft by remember(state.cleanupPolicy) { mutableStateOf<PhotoCleanupPolicy?>(state.cleanupPolicy) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(ScreenBackground),
        containerColor = ScreenBackground,
        floatingActionButton = {
            TextButton(
                modifier = Modifier
                    .testTag("folder_add_media")
                    .height(72.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = Accent,
                    contentColor = Color.White,
                ),
                onClick = onAddMedia,
            ) {
                Text(stringResource(R.string.add_media), fontWeight = FontWeight.SemiBold)
            }
        },
    ) { innerPadding ->
        when {
            state.folder != null -> FolderGridContent(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                folder = state.folder,
                onBack = onBack,
                onOpenPolicy = {
                    policyDraft = state.cleanupPolicy ?: PhotoCleanupPolicy.Keep
                    isPolicyDialogVisible = true
                },
                onDeletePhoto = onDeletePhoto,
                onDownloadPhoto = onDownloadPhoto,
            )

            state.errorMessage != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                )
            }
        }
    }

    if (isPolicyDialogVisible) {
        AlertDialog(
            onDismissRequest = { isPolicyDialogVisible = false },
            containerColor = Panel,
            titleContentColor = Color.White,
            textContentColor = MutedText,
            title = { Text(stringResource(R.string.folder_sync_behavior)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoCleanupPolicy.values().forEach { policy ->
                        PolicyRow(
                            selected = policyDraft == policy,
                            title = policy.label(),
                            subtitle = when (policy) {
                                PhotoCleanupPolicy.Keep -> stringResource(R.string.policy_use_global)
                                PhotoCleanupPolicy.Compress -> stringResource(R.string.policy_keep_compressed_copy)
                                PhotoCleanupPolicy.Delete -> stringResource(R.string.policy_remove_after_sync)
                            },
                            onClick = { policyDraft = policy },
                        )
                    }
                    PolicyRow(
                        selected = policyDraft == null,
                        title = stringResource(R.string.policy_follow_global),
                        subtitle = stringResource(R.string.policy_no_folder_override),
                        onClick = { policyDraft = null },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateCleanupPolicy(policyDraft)
                    isPolicyDialogVisible = false
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { isPolicyDialogVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun FolderGridContent(
    folder: FolderDetail,
    onBack: () -> Unit,
    onOpenPolicy: () -> Unit,
    onDeletePhoto: (String) -> Unit,
    onDownloadPhoto: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FolderHeader(
            folder = folder,
            onBack = onBack,
            onOpenPolicy = onOpenPolicy,
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(
                items = folder.photos,
                key = { it.id },
            ) { photo ->
                PhotoGridCell(
                    photo = photo,
                    onDeletePhoto = { onDeletePhoto(photo.id) },
                    onDownloadPhoto = { onDownloadPhoto(photo.id) },
                )
            }
        }
    }
}

@Composable
private fun FolderHeader(
    folder: FolderDetail,
    onBack: () -> Unit,
    onOpenPolicy: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(modifier = Modifier.testTag("folder_back"), onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color(0xFFC3CEFF),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(folder.name, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.photos_count, folder.photos.size), color = MutedText, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onOpenPolicy) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.settings),
                    tint = Color(0xFFC3CEFF),
                )
            }
        }

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SyncStatsRow(
                    synced = folder.photos.count { it.status == PhotoSyncStatus.Synced },
                    pending = folder.photos.count {
                        it.status == PhotoSyncStatus.Pending ||
                            it.status == PhotoSyncStatus.Uploading ||
                            it.status == PhotoSyncStatus.RemoteOnly
                    },
                    failed = folder.photos.count { it.status == PhotoSyncStatus.Failed },
                )
                SyncProgressBar(progress = folder.progress())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("0%", color = MutedText, style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(
                            R.string.photos_progress,
                            folder.photos.count { it.status == PhotoSyncStatus.Synced },
                            folder.photos.size,
                        ),
                        color = MutedText,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoGridCell(
    photo: PhotoItem,
    onDeletePhoto: () -> Unit,
    onDownloadPhoto: () -> Unit,
) {
    val isRemoteOnly = photo.status == PhotoSyncStatus.RemoteOnly
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .testTag("photo_cell"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(photo.status.containerColor()),
            ) {
                val bitmap = photo.thumbnailPath?.let { BitmapFactory.decodeFile(it) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = photo.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (isRemoteOnly) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xAA07111C)),
                    )
                    TextButton(
                        onClick = onDownloadPhoto,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Accent, RoundedCornerShape(14.dp)),
                    ) {
                        Text(stringResource(R.string.download), color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    IconButton(
                        onClick = onDeletePhoto,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(28.dp)
                            .background(Color(0xAA101825), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.delete_photo),
                            tint = Color.White,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = photo.title,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = photo.status.label(),
                    modifier = Modifier.testTag("photo_status"),
                    color = Color(0xFFC3CEFF),
                    style = MaterialTheme.typography.bodySmall,
                )
                SyncProgressBar(
                    modifier = Modifier.fillMaxWidth(),
                    progress = if (photo.status == PhotoSyncStatus.Synced) 1f else 0.18f,
                )
            }
        }
    }
}

@Composable
private fun SyncStatsRow(
    synced: Int,
    pending: Int,
    failed: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SyncMetric(stringResource(R.string.synced), synced, Success, Modifier.weight(1f))
        SyncMetric(stringResource(R.string.pending), pending, Warning, Modifier.weight(1f))
        SyncMetric(stringResource(R.string.failed), failed, Danger, Modifier.weight(1f))
    }
}

@Composable
private fun SyncMetric(
    label: String,
    value: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value.toString(), color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, color = MutedText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SyncProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF0B1420)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF5968FF), Color(0xFF766DFF)))),
        )
    }
}

@Composable
private fun PolicyRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color(0xFF101927))
            .border(1.dp, if (selected) Accent else PanelStroke, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MutedText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.95f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, PanelStroke.copy(alpha = 0.72f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)))
                .padding(16.dp),
        ) {
            content()
        }
    }
}

private fun FolderDetail.progress(): Float {
    if (photos.isEmpty()) return 0f
    return photos.count { it.status == PhotoSyncStatus.Synced }.toFloat() / photos.size.toFloat()
}

@Composable
private fun PhotoSyncStatus.containerColor() = when (this) {
    PhotoSyncStatus.Synced -> Color(0xFF153A29)
    PhotoSyncStatus.Pending -> Color(0xFF232944)
    PhotoSyncStatus.Uploading -> Color(0xFF252D54)
    PhotoSyncStatus.RemoteOnly -> Color(0xFF1E2938)
    PhotoSyncStatus.Failed -> Color(0xFF4A2022)
}

@Composable
private fun PhotoSyncStatus.label(): String = when (this) {
    PhotoSyncStatus.Synced -> stringResource(R.string.synced)
    PhotoSyncStatus.Pending -> stringResource(R.string.pending)
    PhotoSyncStatus.Uploading -> stringResource(R.string.uploading)
    PhotoSyncStatus.RemoteOnly -> stringResource(R.string.remote_only)
    PhotoSyncStatus.Failed -> stringResource(R.string.failed)
}

@Preview(name = "Folder detail screen", showBackground = true, backgroundColor = 0xFF07111C)
@Composable
private fun FolderDetailScreenPreview() {
    PhotoSyncTheme(darkTheme = true) {
        FolderDetailScreen(
            state = FolderDetailUiState(
                folder = FolderDetail(
                    id = "camera",
                    name = "Camera",
                    photos = listOf(
                        PhotoItem(id = "1", title = "IMG_1042.jpg", status = PhotoSyncStatus.Synced),
                        PhotoItem(id = "2", title = "IMG_1043.jpg", status = PhotoSyncStatus.Uploading),
                        PhotoItem(id = "3", title = "Vacation.png", status = PhotoSyncStatus.Pending),
                        PhotoItem(id = "4", title = "Remote backup.jpg", status = PhotoSyncStatus.RemoteOnly),
                        PhotoItem(id = "5", title = "Broken upload.jpg", status = PhotoSyncStatus.Failed),
                        PhotoItem(id = "6", title = "Family.jpg", status = PhotoSyncStatus.Synced),
                    ),
                ),
                cleanupPolicy = PhotoCleanupPolicy.Keep,
            ),
            onBack = {},
            onAddMedia = {},
            onDeletePhoto = {},
            onDownloadPhoto = {},
            onUpdateCleanupPolicy = {},
        )
    }
}

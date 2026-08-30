package com.photosync.android.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photosync.android.R
import com.photosync.android.domain.model.DashboardStats
import com.photosync.android.domain.model.FolderSummary
import com.photosync.android.ui.theme.PhotoSyncTheme
import androidx.compose.ui.res.stringResource

private enum class FolderViewMode {
    Compact,
    Detailed,
}

private val ScreenBackground = Color(0xFF07111C)
private val Panel = Color(0xFF151E2B)
private val PanelElevated = Color(0xFF1A2433)
private val PanelStroke = Color(0xFF2A3548)
private val Accent = Color(0xFF6270FF)
private val Success = Color(0xFF45D074)
private val Warning = Color(0xFFFFB02E)
private val Danger = Color(0xFFFF5757)
private val MutedText = Color(0xFFA6ADBA)

@Composable
fun HomeScreen(
    state: State<HomeUiState>,
    onAddFolder: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onFolderClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeScreen(
        state = state.value,
        onAddFolder = onAddFolder,
        onRefresh = onRefresh,
        onOpenSettings = onOpenSettings,
        onFolderClick = onFolderClick,
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    state: kotlinx.coroutines.flow.StateFlow<HomeUiState>,
    onAddFolder: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onFolderClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState = state.collectAsStateWithLifecycle()
    HomeScreen(
        state = uiState.value,
        onAddFolder = onAddFolder,
        onRefresh = onRefresh,
        onOpenSettings = onOpenSettings,
        onFolderClick = onFolderClick,
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onAddFolder: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onFolderClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isAddFolderDialogVisible by remember { mutableStateOf(false) }
    val defaultFolderName = stringResource(R.string.folder_name)
    var folderDraft by remember(state.folders.size) { mutableStateOf("$defaultFolderName ${state.folders.size + 1}") }
    var viewMode by rememberSaveable { mutableStateOf(FolderViewMode.Detailed) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(ScreenBackground),
        containerColor = ScreenBackground,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.testTag("home_add_folder"),
                containerColor = Accent,
                contentColor = Color.White,
                onClick = {
                    folderDraft = "$defaultFolderName ${state.folders.size + 1}"
                    isAddFolderDialogVisible = true
                },
            ) {
                Text(stringResource(R.string.add_folder))
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                HomeHeader(onOpenSettings = onOpenSettings)
            }

            item {
                UploadQueueCard(stats = state.stats, onRefresh = onRefresh)
            }

            item {
                FoldersHeader(
                    count = state.folders.size,
                    viewMode = viewMode,
                    onViewModeChange = { viewMode = it },
                )
            }

            when (viewMode) {
                FolderViewMode.Compact -> {
                    items(
                        items = state.folders.chunked(2),
                        key = { row -> row.joinToString(separator = "-") { it.id } },
                    ) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { folder ->
                                CompactFolderCard(
                                    modifier = Modifier.weight(1f),
                                    folder = folder,
                                    onClick = { onFolderClick(folder.id) },
                                )
                            }
                            if (row.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                FolderViewMode.Detailed -> {
                    items(
                        items = state.folders,
                        key = { it.id },
                    ) { folder ->
                        DetailedFolderCard(
                            folder = folder,
                            onClick = { onFolderClick(folder.id) },
                        )
                    }
                }
            }
        }
    }

    if (isAddFolderDialogVisible) {
        AlertDialog(
            onDismissRequest = { isAddFolderDialogVisible = false },
            title = { Text(stringResource(R.string.create_folder)) },
            text = {
                OutlinedTextField(
                    value = folderDraft,
                    onValueChange = { folderDraft = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.folder_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddFolder(folderDraft)
                        isAddFolderDialogVisible = false
                    },
                ) {
                    Text(stringResource(R.string.create))
                }
            },
            dismissButton = {
                TextButton(onClick = { isAddFolderDialogVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "PhotoSync",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.settings),
                tint = Color(0xFFC3CEFF),
            )
        }
    }
}

@Composable
private fun UploadQueueCard(
    stats: DashboardStats,
    onRefresh: () -> Unit,
) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Accent.copy(alpha = 0.24f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("UP", color = Accent, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text(stringResource(R.string.upload_queue), color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.auto_sync_enabled), color = MutedText, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.refresh), color = Color(0xFFC3CEFF))
                }
            }

            SyncStatsRow(
                synced = stats.syncedPhotos,
                pending = stats.pendingPhotos,
                failed = stats.failedPhotos,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncProgressBar(progress = stats.progress())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.photos_progress, stats.syncedPhotos, stats.totalPhotos), color = MutedText)
                    Text("${(stats.progress() * 100).toInt()}%", color = MutedText)
                }
            }
        }
    }
}

@Composable
private fun FoldersHeader(
    count: Int,
    viewMode: FolderViewMode,
    onViewModeChange: (FolderViewMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(stringResource(R.string.folders), color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.folders_total, count), color = Color(0xFFC3CEFF), style = MaterialTheme.typography.bodySmall)
            }
            ViewModeToggle(
                viewMode = viewMode,
                onViewModeChange = onViewModeChange,
            )
        }
    }
}

@Composable
private fun ViewModeToggle(
    viewMode: FolderViewMode,
    onViewModeChange: (FolderViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Panel)
            .border(1.dp, PanelStroke, RoundedCornerShape(24.dp))
            .padding(4.dp)
            .testTag("folder_view_mode_toggle"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToggleOption(
            text = stringResource(R.string.compact),
            selected = viewMode == FolderViewMode.Compact,
            onClick = { onViewModeChange(FolderViewMode.Compact) },
        )
        ToggleOption(
            text = stringResource(R.string.detailed),
            selected = viewMode == FolderViewMode.Detailed,
            onClick = { onViewModeChange(FolderViewMode.Detailed) },
        )
    }
}

@Composable
private fun ToggleOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else MutedText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CompactFolderCard(
    folder: FolderSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.92f)
            .testTag("folder_row")
            .clickable(onClick = onClick),
        contentPadding = PaddingValues(18.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "...",
                modifier = Modifier.align(Alignment.TopEnd),
                color = MutedText,
                style = MaterialTheme.typography.titleLarge,
            )
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FolderPreviewGraphic(
                    thumbnailPaths = folder.previewThumbnailPaths,
                    isEmptyFolder = folder.photoCount == 0,
                    modifier = Modifier.size(width = 128.dp, height = 94.dp),
                )
                Text(
                    text = folder.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (folder.photoCount == 0 || folder.previewThumbnailPaths.isEmpty()) {
                    Text(
                        text = if (folder.photoCount == 0) {
                            stringResource(R.string.empty_folder)
                        } else {
                            stringResource(R.string.no_previews_yet)
                        },
                        color = MutedText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailedFolderCard(
    folder: FolderSummary,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("folder_row")
            .clickable(onClick = onClick),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiniFolderPreviewGraphic(
                        thumbnailPaths = folder.previewThumbnailPaths,
                        isEmptyFolder = folder.photoCount == 0,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = folder.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.photos_count, folder.photoCount),
                            color = MutedText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                StatusBadge(folder)
            }

            Divider(color = PanelStroke)

            SyncStatsRow(
                synced = folder.syncedCount,
                pending = folder.pendingCount,
                failed = folder.failedCount,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SyncProgressBar(
                    modifier = Modifier.weight(1f),
                    progress = folder.progress(),
                )
                Text(
                    text = "${(folder.progress() * 100).toInt()}%",
                    color = MutedText,
                    style = MaterialTheme.typography.titleMedium,
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
        SyncMetric(label = stringResource(R.string.synced), value = synced, color = Success, modifier = Modifier.weight(1f))
        SyncMetric(label = stringResource(R.string.pending), value = pending, color = Warning, modifier = Modifier.weight(1f))
        SyncMetric(label = stringResource(R.string.failed), value = failed, color = Danger, modifier = Modifier.weight(1f))
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
        Text(
            text = value.toString(),
            color = color,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(text = label, color = MutedText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusBadge(folder: FolderSummary) {
    val color = when {
        folder.failedCount > 0 -> Danger
        folder.pendingCount > 0 -> Warning
        else -> Success
    }
    val text = when {
        folder.failedCount > 0 -> "x"
        folder.pendingCount > 0 -> "!"
        else -> "ok"
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF5968FF), Color(0xFF766DFF)),
                    ),
                ),
        )
    }
}

@Composable
private fun FolderPreviewGraphic(
    thumbnailPaths: List<String>,
    isEmptyFolder: Boolean,
    modifier: Modifier = Modifier.size(width = 180.dp, height = 124.dp),
) {
    Box(modifier = modifier) {
        if (thumbnailPaths.isEmpty()) {
            EmptyFolderGraphic(
                modifier = Modifier.align(Alignment.Center),
                dimmed = !isEmptyFolder,
            )
        } else {
            val previews = thumbnailPaths.take(3)
            previews.getOrNull(2)?.let { path ->
                ThumbnailPreview(
                    path = path,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = (-6).dp, y = 4.dp)
                        .graphicsLayer(rotationZ = 6f),
                )
            }
            previews.getOrNull(1)?.let { path ->
                ThumbnailPreview(
                    path = path,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = (-30).dp, y = (-8).dp)
                        .graphicsLayer(rotationZ = -4f),
                )
            }
            previews.getOrNull(0)?.let { path ->
                ThumbnailPreview(
                    path = path,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = (-56).dp, y = 10.dp)
                        .graphicsLayer(rotationZ = 3f),
                )
            }
            FolderShell(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 8.dp, y = (-10).dp)
                    .size(width = 86.dp, height = 86.dp),
                alpha = 0.86f,
            )
        }
    }
}

@Composable
private fun MiniFolderPreviewGraphic(
    thumbnailPaths: List<String>,
    isEmptyFolder: Boolean,
) {
    Box(modifier = Modifier.size(width = 118.dp, height = 86.dp)) {
        if (thumbnailPaths.isEmpty()) {
            EmptyFolderGraphic(
                modifier = Modifier.align(Alignment.Center),
                compact = true,
                dimmed = !isEmptyFolder,
            )
        } else {
            val previews = thumbnailPaths.take(3)
            previews.getOrNull(2)?.let { path ->
                ThumbnailPreview(
                    path = path,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = (-2).dp, y = 4.dp)
                        .graphicsLayer(rotationZ = 5f),
                    width = 54,
                    height = 44,
                )
            }
            previews.getOrNull(1)?.let { path ->
                ThumbnailPreview(
                    path = path,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = (-20).dp, y = (-6).dp)
                        .graphicsLayer(rotationZ = -4f),
                    width = 54,
                    height = 44,
                )
            }
            previews.getOrNull(0)?.let { path ->
                ThumbnailPreview(
                    path = path,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = (-38).dp, y = 8.dp)
                        .graphicsLayer(rotationZ = 3f),
                    width = 54,
                    height = 44,
                )
            }
            FolderShell(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 6.dp, y = (-8).dp)
                    .size(width = 60.dp, height = 60.dp),
                compact = true,
                alpha = 0.86f,
            )
        }
    }
}

@Composable
private fun ThumbnailPreview(
    path: String,
    modifier: Modifier = Modifier,
    width: Int = 82,
    height: Int = 66,
) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
    Box(
        modifier = modifier
            .size(width = width.dp, height = height.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xFF0F1A27))
            .border(1.5.dp, Color.White.copy(alpha = 0.86f), RoundedCornerShape(7.dp)),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF5D7BBF), Color(0xFF172638)))),
            )
        }
    }
}

@Composable
private fun EmptyFolderGraphic(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    dimmed: Boolean = false,
) {
    val width = if (compact) 76.dp else 108.dp
    val height = if (compact) 76.dp else 108.dp
    FolderShell(
        modifier = modifier.size(width = width, height = height),
        compact = compact,
        alpha = if (dimmed) 0.62f else 1f,
    )
}

@Composable
private fun FolderShell(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    alpha: Float = 1f,
) {
    val bodyHeight = if (compact) 50.dp else 70.dp
    val tabWidth = if (compact) 36.dp else 50.dp
    val tabHeight = if (compact) 18.dp else 24.dp
    val tabOffset = if (compact) 6.dp else 8.dp

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bodyHeight)
                .clip(RoundedCornerShape(if (compact) 7.dp else 8.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFFFE37A).copy(alpha = alpha),
                            Color(0xFFFFBF32).copy(alpha = alpha),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = tabOffset)
                .size(width = tabWidth, height = tabHeight)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(Color(0xFFFFE884).copy(alpha = alpha)),
        )
    }
}

@Composable
private fun PreviewPhoto(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    width: Int = 82,
    height: Int = 66,
) {
    Box(
        modifier = modifier
            .size(width = width.dp, height = height.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Brush.linearGradient(colors))
            .border(1.5.dp, Color.White.copy(alpha = 0.86f), RoundedCornerShape(7.dp)),
    )
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = PanelElevated.copy(alpha = 0.94f)),
        border = BorderStroke(1.dp, PanelStroke.copy(alpha = 0.72f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                    ),
                )
                .padding(contentPadding),
        ) {
            content()
        }
    }
}

private fun DashboardStats.progress(): Float {
    if (totalPhotos <= 0) return 0f
    return syncedPhotos.toFloat() / totalPhotos.toFloat()
}

private fun FolderSummary.progress(): Float {
    if (photoCount <= 0) return 0f
    return syncedCount.toFloat() / photoCount.toFloat()
}

@Preview(name = "Home screen", showBackground = true, backgroundColor = 0xFF07111C)
@Composable
private fun HomeScreenPreview() {
    PhotoSyncTheme(darkTheme = true) {
        HomeScreen(
            state = HomeUiState(
                stats = DashboardStats(
                    totalFolders = 3,
                    totalPhotos = 248,
                    syncedPhotos = 186,
                    pendingPhotos = 51,
                    failedPhotos = 11,
                ),
                folders = listOf(
                    FolderSummary(
                        id = "camera",
                        name = "Camera",
                        photoCount = 128,
                        syncedCount = 112,
                        pendingCount = 12,
                        failedCount = 4,
                        statusLabel = "Syncing",
                        previewThumbnailPaths = listOf("preview-camera-1", "preview-camera-2", "preview-camera-3"),
                    ),
                    FolderSummary(
                        id = "family",
                        name = "Family Album",
                        photoCount = 84,
                        syncedCount = 72,
                        pendingCount = 10,
                        failedCount = 2,
                        statusLabel = "Pending",
                        previewThumbnailPaths = listOf("preview-family-1", "preview-family-2"),
                    ),
                    FolderSummary(
                        id = "empty",
                        name = "Screenshots",
                        photoCount = 0,
                        syncedCount = 0,
                        pendingCount = 0,
                        failedCount = 0,
                        statusLabel = "Empty",
                    ),
                ),
            ),
            onAddFolder = {},
            onRefresh = {},
            onOpenSettings = {},
            onFolderClick = {},
        )
    }
}

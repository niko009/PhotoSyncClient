package com.photosync.android.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photosync.android.R
import com.photosync.android.domain.model.*
import com.photosync.android.ui.components.*
import com.photosync.android.ui.theme.PhotoSyncTheme
import kotlinx.coroutines.flow.StateFlow

@Composable
fun HomeScreen(state: State<HomeUiState>, onAddFolder: (String) -> Unit, onRefresh: () -> Unit,
    onOpenSettings: () -> Unit, onFolderClick: (String) -> Unit, modifier: Modifier = Modifier) =
    HomeScreen(state.value, onAddFolder, onRefresh, onOpenSettings, onFolderClick, modifier)

@Composable
fun HomeScreen(state: StateFlow<HomeUiState>, onAddFolder: (String) -> Unit, onRefresh: () -> Unit,
    onOpenSettings: () -> Unit, onFolderClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val ui by state.collectAsStateWithLifecycle()
    HomeScreen(ui, onAddFolder, onRefresh, onOpenSettings, onFolderClick, modifier)
}

@Composable
fun HomeScreen(state: HomeUiState, onAddFolder: (String) -> Unit, onRefresh: () -> Unit,
    onOpenSettings: () -> Unit, onFolderClick: (String) -> Unit, modifier: Modifier = Modifier) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var syncTab by rememberSaveable { mutableStateOf(false) }
    Scaffold(modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Brand()
                IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("home_settings")) {
                    Icon(Icons.Default.Settings, stringResource(R.string.settings), tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(selected = !syncTab, onClick = { syncTab = false },
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text(stringResource(R.string.album_albums)) })
                NavigationBarItem(selected = syncTab, onClick = { syncTab = true },
                    icon = { Icon(Icons.Default.Refresh, null) }, label = { Text(stringResource(R.string.album_sync)) })
            }
        },
        floatingActionButton = {
            if (!syncTab) ExtendedFloatingActionButton(onClick = { draft = ""; adding = true },
                modifier = Modifier.testTag("home_add_folder"),
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.album_new_folder)) })
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).animateContentSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(if (syncTab) R.string.album_sync_title else R.string.album_home_title), style = MaterialTheme.typography.headlineLarge)
                    Text(stringResource(R.string.album_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SyncSummary(state.stats, onRefresh, expanded = syncTab) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(if (syncTab) R.string.folders else R.string.album_my_folders), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.album_private), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                }
            }
            if (state.folders.isEmpty()) item {
                AlbumPanel {
                    Text(stringResource(R.string.album_empty_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.album_empty_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { draft = ""; adding = true }) { Text(stringResource(R.string.create_folder)) }
                }
            }
            if (syncTab) {
                items(state.folders, key = { it.id }) { folder ->
                    AlbumPanel(Modifier.clickable { onFolderClick(folder.id) }) {
                        Text(folder.name, style = MaterialTheme.typography.titleMedium)
                        if (folder.photoCount == 0) {
                            val confirmed = folder.remoteAlbumId != null
                            Text(
                                stringResource(
                                    if (confirmed) R.string.album_folder_synced
                                    else R.string.album_folder_waiting_server,
                                ),
                                color = if (confirmed) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearProgressIndicator(
                                progress = if (confirmed) 1f else 0f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(stringResource(R.string.photos_progress, folder.syncedCount, folder.photoCount))
                            LinearProgressIndicator(
                                progress = folder.syncedCount.toFloat() / folder.photoCount,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (folder.failedCount > 0) Text(stringResource(R.string.album_needs_attention), color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                items(state.folders.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { folder ->
                            AlbumCard(folder, { onFolderClick(folder.id) }, Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false },
        title = { Text(stringResource(R.string.create_folder)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.album_private_hint))
            OutlinedTextField(draft, { draft = it }, singleLine = true,
                modifier = Modifier.testTag("folder_name_input"), label = { Text(stringResource(R.string.folder_name)) })
        } },
        confirmButton = { TextButton(onClick = { onAddFolder(draft.trim()); adding = false }, enabled = draft.isNotBlank()) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun AlbumCard(folder: FolderSummary, onClick: () -> Unit, modifier: Modifier) {
    Surface(modifier.clickable(onClick = onClick).testTag("folder_card"), shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            AlbumImage(folder.previewThumbnailPaths.firstOrNull(), null, Modifier.fillMaxWidth().aspectRatio(1.1f))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.album_private), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                Text(stringResource(R.string.photos_count, folder.photoCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Preview(name = "Family album — light", showBackground = true, locale = "ru")
@Composable
private fun HomePreview() { PhotoSyncTheme(false) { HomeScreen(
    HomeUiState(stats = DashboardStats(connectionStatus = ConnectionStatus.Online),
        folders = listOf(FolderSummary("1", "Лето у моря", 24, 24, 0, 0, ""), FolderSummary("2", "Наши выходные", 12, 12, 0, 0, ""))),
    {}, {}, {}, {}) } }

@Preview(name = "Empty album — dark", showBackground = true, locale = "ru")
@Composable
private fun HomeDarkPreview() { PhotoSyncTheme(true) { HomeScreen(HomeUiState(), {}, {}, {}, {}) }
}

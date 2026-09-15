package com.photosync.android.ui.share

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photosync.android.R

@Composable
fun ShareImportScreen(
    viewModel: ShareImportViewModel,
    sharedMedia: List<Uri>,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onQueued: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(sharedMedia) { viewModel.setSharedUris(sharedMedia) }
    LaunchedEffect(state.isQueued) { if (state.isQueued) onQueued() }
    BackHandler(enabled = !state.isQueueing, onBack = onCancel)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel, enabled = !state.isQueueing) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                }
                Column(Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.share_import_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.share_import_count, state.sharedUris.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isQueueing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(
                    onClick = onSubmit,
                    enabled = state.sharedUris.isNotEmpty() && state.selectedFolderId != null && !state.isQueueing,
                    modifier = Modifier.fillMaxWidth().testTag("share_confirm"),
                ) {
                    Text(stringResource(R.string.share_import_queue))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.share_import_choose_folder), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.share_import_background_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.isLoadingFolders) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } else if (state.folders.isEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            stringResource(R.string.share_import_no_available_folders),
                            Modifier.padding(18.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            } else {
                items(state.folders, key = { it.id }) { folder ->
                    val selected = state.selectedFolderId == folder.id
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.isQueueing) { viewModel.selectFolder(folder.id) }
                            .testTag("share_folder"),
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.selectFolder(folder.id) },
                                enabled = !state.isQueueing,
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(folder.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.photos_count, folder.photoCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            state.errorMessage?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

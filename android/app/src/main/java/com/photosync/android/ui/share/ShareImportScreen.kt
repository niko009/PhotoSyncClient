package com.photosync.android.ui.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photosync.android.R

@Composable
fun ShareImportScreen(
    viewModel: ShareImportViewModel,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creatingFolder by rememberSaveable { mutableStateOf(false) }
    var folderName by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel, enabled = !state.isUploading) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
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
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.isUploading || state.isFinished) {
                    val progress = if (state.totalCount == 0) 0f
                    else state.processedCount.toFloat() / state.totalCount.toFloat()
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.share_import_progress, state.processedCount, state.totalCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (state.isFinished) {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.done))
                    }
                } else {
                    Button(
                        onClick = viewModel::importToSelectedFolder,
                        enabled = state.sharedUris.isNotEmpty() && state.selectedFolderId != null && !state.isUploading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.share_import_upload))
                    }
                }
            }
        },
        floatingActionButton = {
            if (!state.isFinished && !state.isUploading) {
                ExtendedFloatingActionButton(
                    onClick = {
                        folderName = ""
                        creatingFolder = true
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.share_import_new_folder)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.share_import_choose_folder), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.share_import_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.folders.isEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.share_import_no_folders), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { creatingFolder = true }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.create_folder))
                            }
                        }
                    }
                }
            } else {
                items(state.folders, key = { it.id }) { folder ->
                    val selected = state.selectedFolderId == folder.id
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.isUploading) { viewModel.selectFolder(folder.id) },
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
                                enabled = !state.isUploading,
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
                item {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (creatingFolder) {
        AlertDialog(
            onDismissRequest = { if (!state.isUploading) creatingFolder = false },
            title = { Text(stringResource(R.string.share_import_new_folder)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    enabled = !state.isUploading,
                    singleLine = true,
                    label = { Text(stringResource(R.string.folder_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFolderAndImport(folderName)
                        creatingFolder = false
                    },
                    enabled = folderName.isNotBlank() && !state.isUploading,
                ) {
                    Text(stringResource(R.string.share_import_create_and_upload))
                }
            },
            dismissButton = {
                TextButton(onClick = { creatingFolder = false }, enabled = !state.isUploading) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

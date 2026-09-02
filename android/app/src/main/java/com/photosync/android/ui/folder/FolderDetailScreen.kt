package com.photosync.android.ui.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photosync.android.R
import com.photosync.android.domain.model.*
import com.photosync.android.ui.components.*
import com.photosync.android.ui.settings.label
import com.photosync.android.ui.theme.PhotoSyncTheme
import kotlinx.coroutines.flow.StateFlow

@Composable
fun FolderDetailScreen(
    state: StateFlow<FolderDetailUiState>,
    onBack: () -> Unit,
    onAddMedia: () -> Unit,
    onDeletePhoto: (String) -> Unit,
    onDownloadPhoto: (String) -> Unit,
    onUpdateCleanupPolicy: (PhotoCleanupPolicy?) -> Unit,
    onSaveSharing: (String, String, Map<Int, String>) -> Unit = { _, _, _ -> },
    onRefreshSharing: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ui by state.collectAsStateWithLifecycle()
    FolderDetailScreen(
        ui,
        onBack,
        onAddMedia,
        onDeletePhoto,
        onDownloadPhoto,
        onUpdateCleanupPolicy,
        onSaveSharing,
        onRefreshSharing,
        modifier,
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
    onSaveSharing: (String, String, Map<Int, String>) -> Unit = { _, _, _ -> },
    onRefreshSharing: () -> Unit = {},
    modifier: Modifier = Modifier,
) = FolderDetailScreen(
    state.value,
    onBack,
    onAddMedia,
    onDeletePhoto,
    onDownloadPhoto,
    onUpdateCleanupPolicy,
    onSaveSharing,
    onRefreshSharing,
    modifier,
)

@Composable
fun FolderDetailScreen(
    state: FolderDetailUiState,
    onBack: () -> Unit,
    onAddMedia: () -> Unit,
    onDeletePhoto: (String) -> Unit,
    onDownloadPhoto: (String) -> Unit,
    onUpdateCleanupPolicy: (PhotoCleanupPolicy?) -> Unit,
    onSaveSharing: (String, String, Map<Int, String>) -> Unit = { _, _, _ -> },
    onRefreshSharing: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var policyOpen by rememberSaveable { mutableStateOf(false) }
    var policyDraft by remember(state.cleanupPolicy) { mutableStateOf(state.cleanupPolicy) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    var sharingOpen by rememberSaveable { mutableStateOf(false) }
    var sharingModeDraft by remember { mutableStateOf("Private") }
    var familyPermissionDraft by remember { mutableStateOf("View") }
    var selectedPeopleDraft by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    val photo = state.folder?.photos?.firstOrNull { it.id == selectedId }

    fun openSharing() {
        val activeMemberIds = state.sharing.members.mapTo(mutableSetOf()) { it.userId }
        sharingModeDraft = state.sharing.mode
        familyPermissionDraft = state.sharing.familyPermission
        selectedPeopleDraft = state.sharing.selectedPeople.filterKeys { it in activeMemberIds }
        onRefreshSharing()
        sharingOpen = true
    }

    val accessLabel = when (state.sharing.mode) {
        "WholeFamily" -> stringResource(R.string.album_access_family_status)
        "SelectedPeople" -> stringResource(R.string.album_access_selected_status)
        else -> stringResource(R.string.album_private)
    }

    Scaffold(
        modifier.fillMaxSize(),
        topBar = {
            AlbumHeader(
                state.folder?.name ?: stringResource(R.string.folders),
                onBack,
                Modifier.statusBarsPadding().testTag("folder_back"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.sharing.isAvailable) {
                        IconButton(
                            onClick = ::openSharing,
                            enabled = !state.sharing.isSaving,
                            modifier = Modifier.testTag("folder_sharing"),
                        ) {
                            Icon(Icons.Default.Share, stringResource(R.string.album_access_button))
                        }
                    }
                    IconButton(
                        onClick = { policyDraft = state.cleanupPolicy; policyOpen = true },
                        modifier = Modifier.testTag("folder_policy"),
                    ) {
                        Icon(Icons.Default.Settings, stringResource(R.string.folder_sync_behavior))
                    }
                }
            }
        },
        floatingActionButton = {
            if (state.folder != null) {
                ExtendedFloatingActionButton(
                    onClick = onAddMedia,
                    modifier = Modifier.testTag("folder_add_media"),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.album_add_photos)) },
                )
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            GridCells.Adaptive(140.dp),
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        accessLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        stringResource(R.string.photos_count, state.folder?.photos?.size ?: 0),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.sharing.errorMessage?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.folder == null || state.folder.photos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AlbumPanel {
                        Text(
                            stringResource(
                                if (state.errorMessage != null) R.string.album_folder_missing
                                else R.string.album_empty_title,
                            ),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(stringResource(R.string.album_add_hint))
                    }
                }
            }
            items(state.folder?.photos.orEmpty(), key = { it.id }) { item ->
                Column(
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { selectedId = item.id }
                        .testTag("photo_cell")
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    AlbumImage(
                        item.thumbnailPath ?: item.localUri,
                        item.title,
                        Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    Text(
                        item.status.statusText(),
                        Modifier.padding(10.dp).testTag("photo_status"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (item.status == PhotoSyncStatus.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (policyOpen) {
        AlertDialog(
            onDismissRequest = { policyOpen = false },
            title = { Text(stringResource(R.string.folder_sync_behavior)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    (listOf<PhotoCleanupPolicy?>(null) + PhotoCleanupPolicy.values()).forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = policyDraft == option,
                                    role = Role.RadioButton,
                                    onClick = { policyDraft = option },
                                )
                                .padding(vertical = 12.dp),
                        ) {
                            RadioButton(policyDraft == option, onClick = null)
                            Text(
                                option?.label() ?: stringResource(R.string.policy_use_global),
                                Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (policyDraft == PhotoCleanupPolicy.Delete) confirmCleanup = true
                    else {
                        onUpdateCleanupPolicy(policyDraft)
                        policyOpen = false
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { policyOpen = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (sharingOpen) {
        AlertDialog(
            onDismissRequest = { if (!state.sharing.isSaving) sharingOpen = false },
            title = { Text(stringResource(R.string.album_access_title)) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.album_access_help),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Column(Modifier.selectableGroup()) {
                        SharingModeRow(
                            selected = sharingModeDraft == "Private",
                            label = stringResource(R.string.album_access_private),
                            onClick = { sharingModeDraft = "Private" },
                        )
                        SharingModeRow(
                            selected = sharingModeDraft == "WholeFamily",
                            label = stringResource(R.string.album_access_family),
                            onClick = { sharingModeDraft = "WholeFamily" },
                        )
                        SharingModeRow(
                            selected = sharingModeDraft == "SelectedPeople",
                            label = stringResource(R.string.album_access_selected),
                            onClick = { sharingModeDraft = "SelectedPeople" },
                        )
                    }

                    if (sharingModeDraft == "WholeFamily") {
                        HorizontalDivider()
                        Text(stringResource(R.string.album_access_permission), style = MaterialTheme.typography.titleSmall)
                        PermissionChips(
                            permission = familyPermissionDraft,
                            onChange = { familyPermissionDraft = it },
                        )
                    }

                    if (sharingModeDraft == "SelectedPeople") {
                        HorizontalDivider()
                        Text(stringResource(R.string.album_access_members), style = MaterialTheme.typography.titleSmall)
                        if (state.sharing.members.isEmpty()) {
                            Text(
                                stringResource(R.string.album_access_no_members),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.sharing.members.forEach { member ->
                            val selected = selectedPeopleDraft.containsKey(member.userId)
                            val permission = selectedPeopleDraft[member.userId] ?: "View"
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { checked ->
                                            selectedPeopleDraft = selectedPeopleDraft.toMutableMap().apply {
                                                if (checked) put(member.userId, permission) else remove(member.userId)
                                            }
                                        },
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(member.displayName ?: member.email, style = MaterialTheme.typography.bodyLarge)
                                        if (!member.displayName.isNullOrBlank()) {
                                            Text(
                                                member.email,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                if (selected) {
                                    PermissionChips(
                                        permission = permission,
                                        onChange = { newPermission ->
                                            selectedPeopleDraft = selectedPeopleDraft + (member.userId to newPermission)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    state.sharing.errorMessage?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                    if (state.sharing.isSaving) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.sharing.isSaving,
                    onClick = {
                        onSaveSharing(sharingModeDraft, familyPermissionDraft, selectedPeopleDraft)
                        sharingOpen = false
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.sharing.isSaving,
                    onClick = { sharingOpen = false },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text(stringResource(R.string.policy_delete_original)) },
            text = { Text(stringResource(R.string.album_cleanup_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateCleanupPolicy(policyDraft)
                    confirmCleanup = false
                    policyOpen = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanup = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (photo != null) {
        Dialog(
            onDismissRequest = { selectedId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            PhotoSyncTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF211E1B), contentColor = Color(0xFFFAF6EF)) {
                    Column(Modifier.safeDrawingPadding()) {
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { selectedId = null }) {
                                Icon(Icons.Default.Close, stringResource(R.string.back))
                            }
                            Text(photo.title, Modifier.weight(1f), maxLines = 2)
                        }
                        AlbumImage(
                            photo.localUri ?: photo.thumbnailPath,
                            photo.title,
                            Modifier.fillMaxWidth().weight(1f),
                            fit = true,
                        )
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(photo.status.statusText())
                            if (photo.localUri == null) {
                                Text(stringResource(R.string.album_preview_hint), style = MaterialTheme.typography.bodySmall)
                            }
                            if (photo.serverFileId != null) {
                                Button(onClick = { onDownloadPhoto(photo.id) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.album_download_original))
                                }
                            }
                            TextButton(onClick = { removing = true }) {
                                Text(stringResource(R.string.album_remove_local))
                            }
                        }
                    }
                }
            }
        }
    }

    if (removing && photo != null) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.album_remove_local)) },
            text = { Text(stringResource(R.string.album_remove_help)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePhoto(photo.id)
                    removing = false
                    selectedId = null
                }) { Text(stringResource(R.string.album_remove_local)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SharingModeRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PermissionChips(permission: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = permission == "View",
            onClick = { onChange("View") },
            label = { Text(stringResource(R.string.album_access_view)) },
        )
        FilterChip(
            selected = permission == "Contribute",
            onClick = { onChange("Contribute") },
            label = { Text(stringResource(R.string.album_access_contribute)) },
        )
    }
}

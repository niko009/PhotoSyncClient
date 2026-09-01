package com.photosync.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.photosync.android.BuildConfig
import com.photosync.android.R
import com.photosync.android.data.ServerAddress
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.ui.components.*

@Composable
fun SettingsScreen(state: SettingsUiState, onBack: () -> Unit, onSaveServerUrl: (String) -> Unit,
    onSaveGlobalPolicy: (PhotoCleanupPolicy) -> Unit, onGoogleSignIn: () -> Unit,
    onGoogleSignOut: () -> Unit, modifier: Modifier = Modifier) {
    var serverDraft by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var policyDraft by remember(state.globalPolicy) { mutableStateOf(state.globalPolicy) }
    var confirmDelete by remember { mutableStateOf(false) }
    val validAddress = runCatching { ServerAddress.normalize(serverDraft) }.isSuccess
    Scaffold(modifier.fillMaxSize(), topBar = {
        AlbumHeader(stringResource(R.string.settings), onBack,
            Modifier.statusBarsPadding().testTag("settings_back"))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text(stringResource(R.string.album_settings_title), style = MaterialTheme.typography.headlineLarge)
            }
            item {
                AlbumPanel {
                    Text(stringResource(R.string.album_device_access), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.album_device_access_body))
                    Text(stringResource(R.string.album_reset_warning), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                AlbumPanel {
                    Text(stringResource(R.string.google_account), style = MaterialTheme.typography.titleLarge)
                    val account = state.googleAccount
                    if (account == null) {
                        Text(stringResource(R.string.google_account_help), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onGoogleSignIn, enabled = !state.googleBusy,
                            modifier = Modifier.fillMaxWidth().testTag("google_sign_in")) {
                            if (state.googleBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.google_sign_in))
                        }
                    } else {
                        Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(account.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.google_linked_devices, account.linkedDevices),
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onGoogleSignOut, enabled = !state.googleBusy) {
                            Text(stringResource(R.string.google_sign_out))
                        }
                    }
                    if (state.googleError) Text(stringResource(R.string.google_sign_in_error),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                AlbumPanel {
                    Text(stringResource(R.string.server), style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(serverDraft, { serverDraft = it }, Modifier.fillMaxWidth().testTag("server_url"),
                        label = { Text(stringResource(R.string.base_url)) }, singleLine = true,
                        isError = serverDraft.isNotBlank() && !validAddress)
                    Text(stringResource(R.string.album_server_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { onSaveServerUrl(ServerAddress.normalize(serverDraft)) },
                        enabled = validAddress, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.album_save_connect))
                    }
                    Text(stringResource(when (state.stats.connectionStatus) {
                        com.photosync.android.domain.model.ConnectionStatus.Online -> R.string.album_ready
                        com.photosync.android.domain.model.ConnectionStatus.Offline -> R.string.album_offline
                        com.photosync.android.domain.model.ConnectionStatus.Connecting -> R.string.album_connecting
                        else -> R.string.album_not_checked
                    }), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                AlbumPanel {
                    Text(stringResource(R.string.after_sync), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.album_cleanup_help), style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.selectableGroup()) {
                        PhotoCleanupPolicy.values().forEach { policy ->
                            Row(Modifier.fillMaxWidth().selectable(selected = policyDraft == policy,
                                role = Role.RadioButton, onClick = { policyDraft = policy }).padding(vertical = 8.dp)) {
                                RadioButton(selected = policyDraft == policy, onClick = null)
                                Text(policy.label(), Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        if (policyDraft == PhotoCleanupPolicy.Delete) confirmDelete = true
                        else onSaveGlobalPolicy(policyDraft)
                    }) { Text(stringResource(R.string.save_behavior)) }
                }
            }
            item {
                AlbumPanel {
                    Text(stringResource(R.string.about), style = MaterialTheme.typography.titleLarge)
                    Brand()
                    Text(stringResource(R.string.version_format, BuildConfig.VERSION_NAME))
                    Text(stringResource(R.string.album_by_lab), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.album_language_system), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.album_family_later), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.policy_delete_original)) },
        text = { Text(stringResource(R.string.album_cleanup_warning)) },
        confirmButton = { TextButton(onClick = { onSaveGlobalPolicy(policyDraft); confirmDelete = false }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun PhotoCleanupPolicy.label(): String = stringResource(when (this) {
    PhotoCleanupPolicy.Keep -> R.string.policy_keep_description
    PhotoCleanupPolicy.Compress -> R.string.policy_keep_compressed_copy
    PhotoCleanupPolicy.Delete -> R.string.policy_remove_after_sync
})

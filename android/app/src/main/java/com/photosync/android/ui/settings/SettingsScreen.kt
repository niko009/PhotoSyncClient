package com.photosync.android.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.photosync.android.R
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.ui.theme.PhotoSyncTheme

private val ScreenBackground = Color(0xFF07111C)
private val Panel = Color(0xFF172231)
private val PanelStroke = Color(0xFF2A3548)
private val Accent = Color(0xFF6270FF)
private val Success = Color(0xFF45D074)
private val Danger = Color(0xFFFF5757)
private val MutedText = Color(0xFFA6ADBA)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSaveServerUrl: (String) -> Unit,
    onSaveGlobalPolicy: (PhotoCleanupPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    var serverDraft by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var policyDraft by remember(state.globalPolicy) { mutableStateOf(state.globalPolicy) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(ScreenBackground),
        containerColor = ScreenBackground,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                SettingsHeader(onBack = onBack)
            }
            item {
                SectionTitle(title = stringResource(R.string.server))
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        OutlinedTextField(
                            value = serverDraft,
                            onValueChange = { serverDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.base_url)) },
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Success)
                                    .padding(4.dp),
                            )
                            Text(stringResource(R.string.connected), color = MutedText, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Accent.copy(alpha = 0.24f), RoundedCornerShape(10.dp)),
                            onClick = { },
                        ) {
                            Text(stringResource(R.string.test_connection), color = Color(0xFFC3CEFF), fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Accent, RoundedCornerShape(10.dp)),
                            onClick = { onSaveServerUrl(serverDraft) },
                        ) {
                            Text(stringResource(R.string.save_server), color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                SectionTitle(
                    title = stringResource(R.string.after_sync),
                    subtitle = stringResource(R.string.after_sync_description),
                )
                GlassCard {
                    Column(
                        modifier = Modifier.selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PhotoCleanupPolicy.values().forEach { policy ->
                            RowOption(
                            title = policy.label(),
                            subtitle = policy.description(),
                                selected = policyDraft == policy,
                                onClick = { policyDraft = policy },
                            )
                        }
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSaveGlobalPolicy(policyDraft) },
                        ) {
                            Text(stringResource(R.string.save_behavior), color = Color(0xFFC3CEFF), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                SectionTitle(title = stringResource(R.string.language), subtitle = stringResource(R.string.choose_app_language))
                GlassCard(contentPadding = PaddingValues(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.english), color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("v", color = MutedText, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                SectionTitle(title = stringResource(R.string.about))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.app_name), color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.version_format, "0.1.0"), color = MutedText)
                    Text(
                        stringResource(R.string.about_description),
                        color = MutedText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                GlassCard(contentPadding = PaddingValues(14.dp)) {
                    Text(stringResource(R.string.reset_settings), color = Danger, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = Color(0xFFC3CEFF),
            )
        }
        Text(stringResource(R.string.settings), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Box(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Color(0xFFC3CEFF), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(subtitle, color = MutedText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RowOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .background(if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
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
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.95f)),
        border = BorderStroke(1.dp, PanelStroke.copy(alpha = 0.72f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)))
                .padding(contentPadding),
        ) {
            content()
        }
    }
}

@Composable
fun PhotoCleanupPolicy.label(): String = when (this) {
    PhotoCleanupPolicy.Keep -> stringResource(R.string.policy_nothing)
    PhotoCleanupPolicy.Compress -> stringResource(R.string.policy_compress_original)
    PhotoCleanupPolicy.Delete -> stringResource(R.string.policy_delete_original)
}

@Composable
private fun PhotoCleanupPolicy.description(): String = when (this) {
    PhotoCleanupPolicy.Keep -> stringResource(R.string.policy_keep_description)
    PhotoCleanupPolicy.Compress -> stringResource(R.string.policy_compress_description)
    PhotoCleanupPolicy.Delete -> stringResource(R.string.policy_delete_description)
}

@Preview(name = "Settings screen", showBackground = true, backgroundColor = 0xFF07111C)
@Composable
private fun SettingsScreenPreview() {
    PhotoSyncTheme(darkTheme = true) {
        SettingsScreen(
            state = SettingsUiState(
                serverUrl = "http://192.168.1.42:5187",
                globalPolicy = PhotoCleanupPolicy.Compress,
            ),
            onBack = {},
            onSaveServerUrl = {},
            onSaveGlobalPolicy = {},
        )
    }
}

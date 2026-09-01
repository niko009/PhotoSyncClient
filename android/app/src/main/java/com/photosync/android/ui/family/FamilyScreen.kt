package com.photosync.android.ui.family

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.photosync.android.data.FamilyApiClient
import com.photosync.android.data.FamilyApiException
import com.photosync.android.data.GoogleCredentialClient
import com.photosync.android.domain.model.FamilyInfo
import com.photosync.android.domain.model.FamilyInvite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FamilyScreen(
    api: FamilyApiClient,
    pendingInviteToken: String?,
    onInviteHandled: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var family by remember { mutableStateOf<FamilyInfo?>(null) }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var createdInvite by remember { mutableStateOf<FamilyInvite?>(null) }

    fun reload() {
        scope.launch {
            busy = true
            error = null
            runCatching { withContext(Dispatchers.IO) { api.getFamily() } }
                .onSuccess { family = it }
                .onFailure { error = failureMessage(it) }
            busy = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (pendingInviteToken != null) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Family invitation", style = MaterialTheme.typography.titleLarge)
                            Text("Sign in with the Google account this invitation was created for.")
                            Button(
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        error = null
                                        runCatching {
                                            val idToken = GoogleCredentialClient(context).signIn()
                                            withContext(Dispatchers.IO) { api.acceptInvite(pendingInviteToken, idToken) }
                                        }.onSuccess {
                                            onInviteHandled()
                                            reload()
                                        }.onFailure { error = failureMessage(it) }
                                        busy = false
                                    }
                                },
                            ) { Text("Accept with Google") }
                        }
                    }
                }
            }

            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }

            if (busy && family == null) item { CircularProgressIndicator() }

            family?.let { current ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(current.name, style = MaterialTheme.typography.headlineMedium)
                        Text("Your role: ${current.role}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                item { Text("Members", style = MaterialTheme.typography.titleLarge) }
                current.members.forEach { member ->
                    item(key = "member-${member.userId}") {
                        Card {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(member.displayName ?: member.email, style = MaterialTheme.typography.titleMedium)
                                Text(member.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (member.isCurrentUser) "${member.role} · You" else member.role)
                                if (current.role.equals("Owner", true) && !member.isCurrentUser) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            busy = true
                                            runCatching { withContext(Dispatchers.IO) { api.removeMember(member.userId) } }
                                                .onSuccess { reload() }
                                                .onFailure { error = failureMessage(it) }
                                            busy = false
                                        }
                                    }) { Text("Remove member") }
                                }
                            }
                        }
                    }
                }

                if (current.role.equals("Owner", true)) {
                    item {
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Invite member", style = MaterialTheme.typography.titleLarge)
                                Text("Enter the exact Google email that must accept the invitation.")
                                OutlinedTextField(
                                    value = email,
                                    onValueChange = { email = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("Google email") },
                                )
                                Button(
                                    enabled = !busy && email.isNotBlank(),
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            error = null
                                            runCatching { withContext(Dispatchers.IO) { api.createInvite(email) } }
                                                .onSuccess {
                                                    createdInvite = it
                                                    email = ""
                                                    reload()
                                                }
                                                .onFailure { error = failureMessage(it) }
                                            busy = false
                                        }
                                    },
                                ) { Text("Create invite link") }
                            }
                        }
                    }

                    if (current.pendingInvites.isNotEmpty()) {
                        item { Text("Pending invitations", style = MaterialTheme.typography.titleLarge) }
                        current.pendingInvites.forEach { invite ->
                            item(key = "invite-${invite.id}") {
                                Card {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(Modifier.weight(1f)) {
                                            Text(invite.expectedEmail)
                                            Text("Expires: ${invite.expiresAt}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = {
                                            scope.launch {
                                                busy = true
                                                runCatching { withContext(Dispatchers.IO) { api.revokeInvite(invite.id) } }
                                                    .onSuccess { reload() }
                                                    .onFailure { error = failureMessage(it) }
                                                busy = false
                                            }
                                        }) { Text("Revoke") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    createdInvite?.inviteUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { createdInvite = null },
            title = { Text("Invitation ready") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Share this link only with ${createdInvite?.expectedEmail}.")
                    Image(qrBitmap(url).asImageBitmap(), contentDescription = "Invitation QR code", modifier = Modifier.size(220.dp))
                    Text(url, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { shareLink(context, url) }) { Text("Share") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { copyLink(context, url) }) { Text("Copy link") }
                    TextButton(onClick = { createdInvite = null }) { Text("Done") }
                }
            },
        )
    }
}

private fun shareLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(intent, "Share PhotoSync invitation"))
}

private fun copyLink(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PhotoSync invitation", url))
}

private fun qrBitmap(value: String): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
        bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bitmap
}

private fun failureMessage(error: Throwable): String = when (error) {
    is FamilyApiException -> error.userMessage()
    else -> error.message ?: "Family request failed."
}

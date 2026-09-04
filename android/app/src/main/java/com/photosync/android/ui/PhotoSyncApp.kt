package com.photosync.android.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photosync.android.domain.repository.PhotoSyncRepository
import com.photosync.android.ui.folder.FolderDetailScreen
import com.photosync.android.ui.folder.FolderDetailViewModel
import com.photosync.android.ui.home.HomeScreen
import com.photosync.android.ui.home.HomeViewModel
import com.photosync.android.ui.settings.SettingsScreen
import com.photosync.android.ui.settings.SettingsViewModel
import com.photosync.android.ui.family.FamilyScreen
import com.photosync.android.ui.share.ShareImportScreen
import com.photosync.android.ui.share.ShareImportViewModel
import com.photosync.android.data.FamilyApiClient
import com.photosync.android.data.GoogleCredentialClient
import com.photosync.android.update.AppUpdatePrompt
import kotlinx.coroutines.launch

private object PhotoSyncRoute {
    const val home = "home"
    const val folder = "folder"
    const val settings = "settings"
    const val family = "family"
    const val shareImport = "share-import"
    const val folderIdArg = "folderId"

    fun folderPath(folderId: String): String = "$folder/$folderId"
}

@Composable
fun PhotoSyncApp(
    repository: PhotoSyncRepository,
    familyApi: FamilyApiClient,
    pendingInviteToken: String? = null,
    onInviteHandled: () -> Unit = {},
    pendingSharedMedia: List<Uri> = emptyList(),
    onSharedMediaHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    var pendingFolderUploadViewModel by remember { mutableStateOf<FolderDetailViewModel?>(null) }
    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        val viewModel = pendingFolderUploadViewModel
        if (viewModel != null) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.upload(uri)
            }
        }
        pendingFolderUploadViewModel = null
    }

    LaunchedEffect(pendingInviteToken) {
        if (pendingInviteToken != null && navController.currentDestination?.route != PhotoSyncRoute.family) {
            navController.navigate(PhotoSyncRoute.family) { launchSingleTop = true }
        }
    }

    LaunchedEffect(pendingSharedMedia) {
        if (pendingSharedMedia.isNotEmpty() && navController.currentDestination?.route != PhotoSyncRoute.shareImport) {
            navController.navigate(PhotoSyncRoute.shareImport) { launchSingleTop = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = PhotoSyncRoute.home,
        modifier = modifier,
    ) {
        composable(route = PhotoSyncRoute.home) {
            val viewModel: HomeViewModel = viewModel(
                factory = HomeViewModel.Factory(repository),
            )
            HomeScreen(
                state = viewModel.state,
                onAddFolder = viewModel::addFolder,
                onRefresh = viewModel::refresh,
                onOpenSettings = { navController.navigate(PhotoSyncRoute.settings) },
                onFolderClick = { folderId ->
                    navController.navigate(PhotoSyncRoute.folderPath(folderId))
                },
            )
        }

        composable(route = PhotoSyncRoute.settings) {
            val viewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(repository),
            )
            val uiState = viewModel.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            SettingsScreen(
                state = uiState.value,
                onBack = navController::popBackStack,
                onSaveServerUrl = viewModel::saveServerUrl,
                onSaveGlobalPolicy = viewModel::saveGlobalPolicy,
                onGoogleSignIn = {
                    scope.launch {
                        runCatching { GoogleCredentialClient(context).signIn() }
                            .onSuccess(viewModel::signInWithGoogle)
                            .onFailure { viewModel.googleCredentialFailed() }
                    }
                },
                onGoogleSignOut = viewModel::signOutFromGoogle,
                onOpenFamily = { navController.navigate(PhotoSyncRoute.family) },
            )
        }

        composable(route = PhotoSyncRoute.family) {
            FamilyScreen(
                api = familyApi,
                pendingInviteToken = pendingInviteToken,
                onInviteHandled = onInviteHandled,
                onBack = navController::popBackStack,
            )
        }

        composable(route = PhotoSyncRoute.shareImport) {
            val viewModel: ShareImportViewModel = viewModel(
                factory = ShareImportViewModel.Factory(repository),
            )
            LaunchedEffect(pendingSharedMedia) {
                viewModel.setSharedUris(pendingSharedMedia)
            }
            val finishShare: () -> Unit = {
                onSharedMediaHandled()
                navController.popBackStack()
            }
            ShareImportScreen(
                viewModel = viewModel,
                onCancel = finishShare,
                onDone = finishShare,
            )
        }

        composable(
            route = "${PhotoSyncRoute.folder}/{${PhotoSyncRoute.folderIdArg}}",
            arguments = listOf(
                navArgument(PhotoSyncRoute.folderIdArg) {
                    type = NavType.StringType
                },
            ),
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString(PhotoSyncRoute.folderIdArg).orEmpty()
            val viewModel: FolderDetailViewModel = viewModel(
                factory = FolderDetailViewModel.Factory(
                    folderId = folderId,
                    repository = repository,
                    familyApi = familyApi,
                ),
            )
            FolderDetailScreen(
                state = viewModel.state,
                onBack = navController::popBackStack,
                onAddMedia = {
                    pendingFolderUploadViewModel = viewModel
                    mediaPicker.launch(arrayOf("image/*", "video/*"))
                },
                onDeletePhoto = viewModel::deletePhoto,
                onDownloadPhoto = viewModel::downloadPhoto,
                onUpdateCleanupPolicy = viewModel::updateCleanupPolicy,
                onSaveSharing = viewModel::saveSharing,
                onRefreshSharing = viewModel::refreshSharing,
            )
        }
    }

    AppUpdatePrompt(onReadyForSync = {
        (context.applicationContext as? com.photosync.android.PhotoSyncApplication)
            ?.container?.startBackgroundSync()
        appScope.launch { repository.refresh() }
    })
}

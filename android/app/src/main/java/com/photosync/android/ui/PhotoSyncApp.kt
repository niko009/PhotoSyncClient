package com.photosync.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.core.content.ContextCompat
import com.photosync.android.domain.repository.PhotoSyncRepository
import com.photosync.android.ui.folder.FolderDetailScreen
import com.photosync.android.ui.folder.FolderDetailViewModel
import com.photosync.android.ui.home.HomeScreen
import com.photosync.android.ui.home.HomeViewModel
import com.photosync.android.ui.settings.SettingsScreen
import com.photosync.android.ui.settings.SettingsViewModel
import com.photosync.android.ui.family.FamilyScreen
import com.photosync.android.data.FamilyApiClient
import com.photosync.android.data.GoogleCredentialClient
import com.photosync.android.data.MediaCleanupManager
import com.photosync.android.update.AppUpdatePrompt
import kotlinx.coroutines.launch

private object PhotoSyncRoute {
    const val home = "home"
    const val folder = "folder"
    const val settings = "settings"
    const val family = "family"
    const val folderIdArg = "folderId"

    fun folderPath(folderId: String): String = "$folder/$folderId"
}

@Composable
fun PhotoSyncApp(
    repository: PhotoSyncRepository,
    familyApi: FamilyApiClient,
    mediaCleanupManager: MediaCleanupManager? = null,
    pendingInviteToken: String? = null,
    onInviteHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    var pendingFolderUploadViewModel by remember { mutableStateOf<FolderDetailViewModel?>(null) }
    var pendingDownload by remember { mutableStateOf<Pair<FolderDetailViewModel, String>?>(null) }
    val storagePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pendingDownload?.let { (viewModel, photoId) -> viewModel.downloadPhoto(photoId) }
        pendingDownload = null
    }
    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(50),
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
                    mediaPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                },
                onDeletePhoto = viewModel::deletePhoto,
                onDownloadPhoto = { photoId ->
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingDownload = viewModel to photoId
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        viewModel.downloadPhoto(photoId)
                    }
                },
                onUpdateCleanupPolicy = viewModel::updateCleanupPolicy,
                onSaveSharing = viewModel::saveSharing,
                onRefreshSharing = viewModel::refreshSharing,
            )
        }
    }

    LaunchedEffect(repository) {
        (context.applicationContext as? com.photosync.android.PhotoSyncApplication)
            ?.container?.startBackgroundSync()
        repository.refresh()
    }

    AppUpdatePrompt()
    mediaCleanupManager?.let { MediaDeletionEffect(it) }
}

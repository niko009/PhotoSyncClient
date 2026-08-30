package com.photosync.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

private object PhotoSyncRoute {
    const val home = "home"
    const val folder = "folder"
    const val settings = "settings"
    const val folderIdArg = "folderId"

    fun folderPath(folderId: String): String = "$folder/$folderId"
}

@Composable
fun PhotoSyncApp(
    repository: PhotoSyncRepository,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    var pendingFolderUploadViewModel by remember { mutableStateOf<FolderDetailViewModel?>(null) }
    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        val viewModel = pendingFolderUploadViewModel
        if (viewModel != null) {
            uris.forEach { uri ->
                viewModel.upload(uri)
            }
        }
        pendingFolderUploadViewModel = null
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
            SettingsScreen(
                state = uiState.value,
                onBack = navController::popBackStack,
                onSaveServerUrl = viewModel::saveServerUrl,
                onSaveGlobalPolicy = viewModel::saveGlobalPolicy,
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
                ),
            )
            FolderDetailScreen(
                state = viewModel.state,
                onBack = navController::popBackStack,
                onAddMedia = {
                    pendingFolderUploadViewModel = viewModel
                    mediaPicker.launch("image/*")
                },
                onDeletePhoto = viewModel::deletePhoto,
                onDownloadPhoto = viewModel::downloadPhoto,
                onUpdateCleanupPolicy = viewModel::updateCleanupPolicy,
            )
        }
    }
}

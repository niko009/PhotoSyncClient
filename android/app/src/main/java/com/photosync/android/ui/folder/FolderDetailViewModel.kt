package com.photosync.android.ui.folder

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photosync.android.domain.model.FolderDetail
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class FolderDetailUiState(
    val folder: FolderDetail? = null,
    val errorMessage: String? = null,
    val cleanupPolicy: PhotoCleanupPolicy? = null,
) {
    val isEmpty: Boolean
        get() = folder == null && errorMessage != null
}

class FolderDetailViewModel(
    private val folderId: String,
    private val repository: PhotoSyncRepository,
) : ViewModel() {

    val state: StateFlow<FolderDetailUiState> = repository.observeFolder(folderId)
        .combine(repository.observeFolderPhotoCleanupPolicy(folderId)) { folder, policy ->
            if (folder == null) {
                FolderDetailUiState(errorMessage = "Folder not found.", cleanupPolicy = policy)
            } else {
                FolderDetailUiState(folder = folder, cleanupPolicy = policy)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = FolderDetailUiState(),
        )

    fun upload(uri: Uri) {
        viewModelScope.launch {
            repository.uploadToFolder(folderId, uri)
        }
    }

    fun deletePhoto(photoId: String) {
        viewModelScope.launch {
            repository.deletePhoto(folderId, photoId)
        }
    }

    fun downloadPhoto(photoId: String) {
        viewModelScope.launch {
            repository.downloadPhoto(folderId, photoId)
        }
    }

    fun updateCleanupPolicy(policy: PhotoCleanupPolicy?) {
        viewModelScope.launch {
            repository.updateFolderPhotoCleanupPolicy(folderId, policy)
        }
    }

    class Factory(
        private val folderId: String,
        private val repository: PhotoSyncRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FolderDetailViewModel(folderId, repository) as T
        }
    }
}

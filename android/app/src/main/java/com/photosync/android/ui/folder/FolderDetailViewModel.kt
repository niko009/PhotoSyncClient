package com.photosync.android.ui.folder

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photosync.android.data.FamilyApiClient
import com.photosync.android.data.FamilyApiException
import com.photosync.android.domain.model.FamilyMember
import com.photosync.android.domain.model.FolderDetail
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FolderSharingUiState(
    val isAvailable: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val albumId: Int? = null,
    val mode: String = "Private",
    val familyPermission: String = "View",
    val members: List<FamilyMember> = emptyList(),
    val selectedPeople: Map<Int, String> = emptyMap(),
    val errorMessage: String? = null,
)

data class FolderDetailUiState(
    val folder: FolderDetail? = null,
    val errorMessage: String? = null,
    val cleanupPolicy: PhotoCleanupPolicy? = null,
    val sharing: FolderSharingUiState = FolderSharingUiState(),
) {
    val isEmpty: Boolean
        get() = folder == null && errorMessage != null
}

class FolderDetailViewModel(
    private val folderId: String,
    private val repository: PhotoSyncRepository,
    private val familyApi: FamilyApiClient,
) : ViewModel() {
    private val sharing = MutableStateFlow(FolderSharingUiState())

    val state: StateFlow<FolderDetailUiState> = combine(
        repository.observeFolder(folderId),
        repository.observeFolderPhotoCleanupPolicy(folderId),
        sharing,
    ) { folder, policy, sharingState ->
        if (folder == null) {
            FolderDetailUiState(
                errorMessage = "Folder not found.",
                cleanupPolicy = policy,
                sharing = sharingState,
            )
        } else {
            FolderDetailUiState(
                folder = folder,
                cleanupPolicy = policy,
                sharing = sharingState,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = FolderDetailUiState(),
    )

    init {
        viewModelScope.launch {
            repository.observeFolder(folderId)
                .filterNotNull()
                .map { folder -> FolderIdentity(folder.name, folder.remoteAlbumId, folder.ownedByMe) }
                .distinctUntilChanged()
                .collect { identity -> loadSharing(identity) }
        }
    }

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

    fun refreshSharing() {
        state.value.folder?.let { folder ->
            viewModelScope.launch {
                loadSharing(FolderIdentity(folder.name, folder.remoteAlbumId, folder.ownedByMe))
            }
        }
    }

    fun saveSharing(mode: String, familyPermission: String, selectedPeople: Map<Int, String>) {
        val current = sharing.value
        val albumId = current.albumId ?: return
        if (current.isSaving) return

        sharing.value = current.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    familyApi.updateAlbumSharing(
                        albumId = albumId,
                        mode = mode,
                        familyPermission = familyPermission,
                        selectedPeople = if (mode == "SelectedPeople") selectedPeople else null,
                    )
                    familyApi.getAlbumSharing(albumId)
                }
            }.onSuccess { settings ->
                sharing.value = current.copy(
                    isAvailable = true,
                    isSaving = false,
                    albumId = settings.albumId,
                    mode = settings.mode,
                    familyPermission = settings.familyPermission,
                    selectedPeople = settings.selectedPeople,
                    errorMessage = null,
                )
                repository.refresh()
            }.onFailure { error ->
                sharing.value = current.copy(
                    isSaving = false,
                    errorMessage = sharingFailureMessage(error),
                )
            }
        }
    }

    private suspend fun loadSharing(identity: FolderIdentity) {
        if (!identity.ownedByMe) {
            sharing.value = FolderSharingUiState(isAvailable = false)
            return
        }

        sharing.value = sharing.value.copy(isLoading = true, errorMessage = null)
        runCatching {
            withContext(Dispatchers.IO) {
                val family = familyApi.getFamily()
                val albumId = identity.remoteAlbumId
                    ?: familyApi.getCurrentDeviceAlbumId(identity.name)
                    ?: familyApi.getAccessibleAlbums()
                        .firstOrNull { it.ownedByMe && it.name == identity.name }
                        ?.albumId
                    ?: return@withContext null
                val settings = familyApi.getAlbumSharing(albumId)
                settings to family.members.filterNot { it.isCurrentUser }
            }
        }.onSuccess { result ->
            if (result == null) {
                sharing.value = FolderSharingUiState(isAvailable = false)
            } else {
                val (settings, members) = result
                sharing.value = FolderSharingUiState(
                    isAvailable = true,
                    isLoading = false,
                    albumId = settings.albumId,
                    mode = settings.mode,
                    familyPermission = settings.familyPermission,
                    members = members,
                    selectedPeople = settings.selectedPeople,
                )
            }
        }.onFailure { error ->
            if (error is FamilyApiException && error.statusCode in listOf(401, 403, 404)) {
                sharing.value = FolderSharingUiState(isAvailable = false)
            } else {
                sharing.value = FolderSharingUiState(
                    isAvailable = false,
                    isLoading = false,
                    errorMessage = sharingFailureMessage(error),
                )
            }
        }
    }

    private fun sharingFailureMessage(error: Throwable): String = when (error) {
        is FamilyApiException -> error.userMessage()
        else -> error.message ?: "Could not update folder access."
    }

    private data class FolderIdentity(
        val name: String,
        val remoteAlbumId: Int?,
        val ownedByMe: Boolean,
    )

    class Factory(
        private val folderId: String,
        private val repository: PhotoSyncRepository,
        private val familyApi: FamilyApiClient,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FolderDetailViewModel(folderId, repository, familyApi) as T
        }
    }
}

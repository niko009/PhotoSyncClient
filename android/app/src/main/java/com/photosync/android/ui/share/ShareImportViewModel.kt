package com.photosync.android.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photosync.android.domain.model.FolderSummary
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareImportUiState(
    val folders: List<FolderSummary> = emptyList(),
    val sharedUris: List<Uri> = emptyList(),
    val selectedFolderId: String? = null,
    val isLoadingFolders: Boolean = true,
    val isQueueing: Boolean = false,
    val isQueued: Boolean = false,
    val errorMessage: String? = null,
)

class ShareImportViewModel(
    private val repository: PhotoSyncRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ShareImportUiState())
    val state: StateFlow<ShareImportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeFolders().collect { allFolders ->
                val folders = allFolders.filter { it.canContribute }
                _state.update { current ->
                    current.copy(
                        folders = folders,
                        selectedFolderId = current.selectedFolderId
                            ?.takeIf { id -> folders.any { it.id == id } },
                        isLoadingFolders = false,
                    )
                }
            }
        }
        viewModelScope.launch {
            runCatching { repository.refresh() }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoadingFolders = false,
                            errorMessage = error.message ?: "Could not load folders.",
                        )
                    }
                }
        }
    }

    fun setSharedUris(uris: List<Uri>) {
        val unique = uris.distinct()
        if (unique == _state.value.sharedUris || _state.value.isQueueing) return
        _state.update {
            it.copy(
                sharedUris = unique,
                selectedFolderId = null,
                isQueued = false,
                errorMessage = null,
            )
        }
    }

    fun selectFolder(folderId: String) {
        if (_state.value.isQueueing) return
        _state.update { it.copy(selectedFolderId = folderId, errorMessage = null) }
    }

    fun enqueueToSelectedFolder() {
        val folderId = _state.value.selectedFolderId
        if (folderId == null) {
            _state.update { it.copy(errorMessage = "Choose a folder first.") }
            return
        }
        if (_state.value.isQueueing) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isQueueing = true,
                    isQueued = false,
                    errorMessage = null,
                )
            }
            runCatching {
                check(repository.enqueueSharedMedia(folderId, _state.value.sharedUris)) {
                    "Could not queue the selected media."
                }
            }
                .onSuccess { _state.update { it.copy(isQueueing = false, isQueued = true) } }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isQueueing = false,
                            errorMessage = error.message ?: "Could not queue the selected media.",
                        )
                    }
                }
        }
    }

    class Factory(
        private val repository: PhotoSyncRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ShareImportViewModel(repository) as T
    }
}

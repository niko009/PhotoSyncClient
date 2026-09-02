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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareImportUiState(
    val folders: List<FolderSummary> = emptyList(),
    val sharedUris: List<Uri> = emptyList(),
    val selectedFolderId: String? = null,
    val isUploading: Boolean = false,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val isFinished: Boolean = false,
    val errorMessage: String? = null,
)

class ShareImportViewModel(
    private val repository: PhotoSyncRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ShareImportUiState())
    val state: StateFlow<ShareImportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeFolders().collect { folders ->
                _state.update { current ->
                    val selected = current.selectedFolderId
                        ?.takeIf { id -> folders.any { it.id == id } }
                        ?: folders.firstOrNull()?.id
                    current.copy(folders = folders, selectedFolderId = selected)
                }
            }
        }
    }

    fun setSharedUris(uris: List<Uri>) {
        val unique = uris.distinct()
        if (unique == _state.value.sharedUris || _state.value.isUploading) return
        _state.update {
            it.copy(
                sharedUris = unique,
                processedCount = 0,
                totalCount = unique.size,
                isFinished = false,
                errorMessage = null,
            )
        }
    }

    fun selectFolder(folderId: String) {
        if (_state.value.isUploading) return
        _state.update { it.copy(selectedFolderId = folderId, errorMessage = null) }
    }

    fun importToSelectedFolder() {
        val folderId = _state.value.selectedFolderId
        if (folderId == null) {
            _state.update { it.copy(errorMessage = "Choose a folder first.") }
            return
        }
        importToFolder(folderId)
    }

    fun createFolderAndImport(name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) {
            _state.update { it.copy(errorMessage = "Folder name cannot be empty.") }
            return
        }
        if (_state.value.isUploading) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isUploading = true,
                    processedCount = 0,
                    totalCount = it.sharedUris.size,
                    isFinished = false,
                    errorMessage = null,
                )
            }

            runCatching {
                repository.addFolder(normalized)
                repository.refresh()
                val folder = repository.observeFolders().first()
                    .firstOrNull { it.name.equals(normalized, ignoreCase = true) }
                    ?: error("Created folder was not found.")
                uploadAll(folder.id)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isUploading = false,
                        errorMessage = error.message ?: "Could not create the folder.",
                    )
                }
            }
        }
    }

    private fun importToFolder(folderId: String) {
        if (_state.value.isUploading) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isUploading = true,
                    processedCount = 0,
                    totalCount = it.sharedUris.size,
                    isFinished = false,
                    errorMessage = null,
                )
            }
            uploadAll(folderId)
        }
    }

    private suspend fun uploadAll(folderId: String) {
        val uris = _state.value.sharedUris
        if (uris.isEmpty()) {
            _state.update { it.copy(isUploading = false, errorMessage = "No media was shared.") }
            return
        }

        uris.forEachIndexed { index, uri ->
            repository.uploadToFolder(folderId, uri)
            _state.update { it.copy(processedCount = index + 1) }
        }
        repository.refresh()
        _state.update {
            it.copy(
                isUploading = false,
                isFinished = true,
                processedCount = uris.size,
            )
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

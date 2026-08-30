package com.photosync.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photosync.android.domain.model.DashboardStats
import com.photosync.android.domain.model.FolderSummary
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val serverUrl: String = "",
    val stats: DashboardStats = DashboardStats(),
    val folders: List<FolderSummary> = emptyList(),
)

class HomeViewModel(
    private val repository: PhotoSyncRepository,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        repository.observeServerUrl(),
        repository.observeStats(),
        repository.observeFolders(),
    ) { serverUrl, stats, folders ->
        HomeUiState(
            serverUrl = serverUrl,
            stats = stats,
            folders = folders,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState(),
    )

    fun addFolder(name: String) {
        val normalized = name.trim()
        if (normalized.isEmpty()) {
            return
        }

        viewModelScope.launch {
            repository.addFolder(normalized)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
        }
    }

    fun updateServerUrl(serverUrl: String) {
        viewModelScope.launch {
            repository.updateServerUrl(serverUrl)
        }
    }

    class Factory(
        private val repository: PhotoSyncRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository) as T
        }
    }
}

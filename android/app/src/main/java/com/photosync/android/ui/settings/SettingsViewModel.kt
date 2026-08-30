package com.photosync.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val globalPolicy: PhotoCleanupPolicy = PhotoCleanupPolicy.Keep,
)

class SettingsViewModel(
    private val repository: PhotoSyncRepository,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(
        repository.observeServerUrl(),
        repository.observeGlobalPhotoCleanupPolicy(),
    ) { serverUrl, globalPolicy ->
        SettingsUiState(
            serverUrl = serverUrl,
            globalPolicy = globalPolicy,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(),
    )

    fun saveServerUrl(serverUrl: String) {
        viewModelScope.launch {
            repository.updateServerUrl(serverUrl)
        }
    }

    fun saveGlobalPolicy(policy: PhotoCleanupPolicy) {
        viewModelScope.launch {
            repository.updateGlobalPhotoCleanupPolicy(policy)
        }
    }

    class Factory(
        private val repository: PhotoSyncRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(repository) as T
        }
    }
}

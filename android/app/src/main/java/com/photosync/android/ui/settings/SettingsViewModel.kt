package com.photosync.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.model.GoogleAccount
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val globalPolicy: PhotoCleanupPolicy = PhotoCleanupPolicy.Keep,
    val stats: com.photosync.android.domain.model.DashboardStats = com.photosync.android.domain.model.DashboardStats(),
    val googleAccount: GoogleAccount? = null,
    val googleBusy: Boolean = false,
    val googleError: Boolean = false,
    val actionError: String? = null,
)

class SettingsViewModel(
    private val repository: PhotoSyncRepository,
) : ViewModel() {
    private val googleBusy = MutableStateFlow(false)
    private val googleError = MutableStateFlow(false)
    private val actionError = MutableStateFlow<String?>(null)
    private val baseState = combine(
        repository.observeServerUrl(),
        repository.observeGlobalPhotoCleanupPolicy(),
        repository.observeStats(),
        repository.observeGoogleAccount(),
    ) { serverUrl, globalPolicy, stats, googleAccount ->
        SettingsUiState(
            serverUrl = serverUrl,
            globalPolicy = globalPolicy,
            stats = stats,
            googleAccount = googleAccount,
        )
    }
    val state: StateFlow<SettingsUiState> = combine(baseState, googleBusy, googleError, actionError) { base, busy, error, action ->
        base.copy(googleBusy = busy, googleError = error, actionError = action)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(),
    )

    fun saveServerUrl(serverUrl: String) {
        viewModelScope.launch {
            actionError.value = null
            runCatching { repository.updateServerUrl(serverUrl) }
                .onFailure { actionError.value = it.message }
        }
    }

    fun saveGlobalPolicy(policy: PhotoCleanupPolicy) {
        viewModelScope.launch {
            repository.updateGlobalPhotoCleanupPolicy(policy)
        }
    }

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            googleBusy.value = true
            googleError.value = false
            actionError.value = null
            runCatching { repository.signInWithGoogle(idToken) }
                .onFailure { googleError.value = true; actionError.value = it.message }
            googleBusy.value = false
        }
    }

    fun googleCredentialFailed() { googleError.value = true }

    fun signOutFromGoogle() {
        viewModelScope.launch {
            googleBusy.value = true
            googleError.value = false
            actionError.value = null
            runCatching { repository.signOutFromGoogle() }
                .onFailure { googleError.value = true; actionError.value = it.message }
            googleBusy.value = false
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

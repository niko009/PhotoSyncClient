package com.photosync.android.data

import android.content.Context
import com.photosync.android.domain.model.PhotoCleanupPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val serverUrlState = MutableStateFlow(
        preferences.getString(KEY_SERVER_URL, PhotoSyncApiClient.DEFAULT_BASE_URL)
            ?: PhotoSyncApiClient.DEFAULT_BASE_URL
    )
    private val globalCleanupPolicyState = MutableStateFlow(
        PhotoCleanupPolicy.valueOf(
            preferences.getString(KEY_GLOBAL_POLICY, PhotoCleanupPolicy.Keep.name) ?: PhotoCleanupPolicy.Keep.name
        )
    )

    fun observeServerUrl(): Flow<String> = serverUrlState.asStateFlow()
    fun observeGlobalPhotoCleanupPolicy(): Flow<PhotoCleanupPolicy> = globalCleanupPolicyState.asStateFlow()

    fun getServerUrl(): String = serverUrlState.value
    fun getGlobalPhotoCleanupPolicy(): PhotoCleanupPolicy = globalCleanupPolicyState.value

    fun updateServerUrl(serverUrl: String) {
        val normalized = serverUrl.trim().ifBlank { PhotoSyncApiClient.DEFAULT_BASE_URL }
        preferences.edit().putString(KEY_SERVER_URL, normalized).apply()
        serverUrlState.value = normalized
    }

    fun updateGlobalPhotoCleanupPolicy(policy: PhotoCleanupPolicy) {
        preferences.edit().putString(KEY_GLOBAL_POLICY, policy.name).apply()
        globalCleanupPolicyState.value = policy
    }

    companion object {
        private const val PREFERENCES_NAME = "photosync_preferences"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_GLOBAL_POLICY = "global_cleanup_policy"
    }
}

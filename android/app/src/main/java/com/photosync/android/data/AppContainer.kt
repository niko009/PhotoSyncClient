package com.photosync.android.data

import android.content.Context
import com.photosync.android.domain.repository.PhotoSyncRepository

interface AppContainer {
    val photoSyncRepository: PhotoSyncRepository
}

class DefaultAppContainer(
    private val context: Context,
) : AppContainer {
    override val photoSyncRepository: PhotoSyncRepository by lazy {
        val preferencesStore = PreferencesStore(context)
        NetworkPhotoSyncRepository(
            context = context,
            apiClient = PhotoSyncApiClient(preferencesStore.getServerUrl()),
            preferencesStore = preferencesStore,
        )
    }
}

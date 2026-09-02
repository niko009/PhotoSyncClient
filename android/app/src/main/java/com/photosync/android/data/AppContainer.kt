package com.photosync.android.data

import android.content.Context
import com.photosync.android.domain.repository.PhotoSyncRepository

interface AppContainer {
    val photoSyncRepository: PhotoSyncRepository
    val familyApiClient: FamilyApiClient
}

class DefaultAppContainer(
    private val context: Context,
) : AppContainer {
    private val preferencesStore by lazy { PreferencesStore(context) }
    private val deviceIdentity by lazy { DeviceIdentity(context) }

    override val familyApiClient: FamilyApiClient by lazy {
        FamilyApiClient(preferencesStore, deviceIdentity)
    }

    override val photoSyncRepository: PhotoSyncRepository by lazy {
        RetryingPhotoSyncRepository(
            NetworkPhotoSyncRepository(
                context = context,
                apiClient = PhotoSyncApiClient(preferencesStore.getServerUrl(), deviceIdentity),
                preferencesStore = preferencesStore,
            ),
        )
    }
}

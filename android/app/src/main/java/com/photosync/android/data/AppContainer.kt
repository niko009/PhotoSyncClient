package com.photosync.android.data

import android.content.Context
import com.photosync.android.domain.repository.PhotoSyncRepository

interface AppContainer {
    val photoSyncRepository: PhotoSyncRepository
    val familyApiClient: FamilyApiClient
    fun startBackgroundSync()
}

class DefaultAppContainer(
    private val context: Context,
    private val observeNetworkChanges: Boolean = true,
) : AppContainer {
    private val preferencesStore by lazy { PreferencesStore(context) }
    private val deviceIdentity by lazy { DeviceIdentity(context) }
    private var syncObserver: NetworkSyncObserver? = null

    override val familyApiClient: FamilyApiClient by lazy {
        FamilyApiClient(preferencesStore, deviceIdentity)
    }

    override val photoSyncRepository: PhotoSyncRepository by lazy {
        val diagnostics = DiagnosticLog(context)
        val networkRepository = NetworkPhotoSyncRepository(
            context = context,
            apiClient = PhotoSyncApiClient(preferencesStore.getServerUrl(), deviceIdentity, diagnostics),
            preferencesStore = preferencesStore,
        )
        val legacyRetryRepository = RetryingPhotoSyncRepository(networkRepository)
        val offlineFirstRepository = OfflineFirstPhotoSyncRepository(
            context = context,
            delegate = legacyRetryRepository,
        )
        offlineFirstRepository
    }

    override fun startBackgroundSync() {
        if (!observeNetworkChanges || syncObserver != null) return
        syncObserver = NetworkSyncObserver(context, photoSyncRepository).also { it.start() }
    }
}

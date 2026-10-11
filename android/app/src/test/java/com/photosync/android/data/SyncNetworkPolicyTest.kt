package com.photosync.android.data

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Uri
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SyncNetworkPolicyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(ConnectivityManager::class.java)
    private val uploads = AtomicInteger()
    private val uploaded = CountDownLatch(1)
    @Before fun setup() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        context.getSharedPreferences("photosync_preferences", 0).edit().clear().commit()
        network(false)
    }
    private fun network(wifi: Boolean, validated: Boolean = true, metered: Boolean = !wifi) {
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            if (wifi) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE, 0, true, true)
        shadowOf(manager).setActiveNetworkInfo(info)
        val caps = NetworkCapabilities()
        shadowOf(caps).addTransportType(if (wifi) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!metered) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, caps)
    }
    private fun repository(): OfflineFirstPhotoSyncRepository {
        val base = FakePhotoSyncRepository(seedFolders = listOf(FolderRecord("folder-1", "Camera", emptyList())))
        return OfflineFirstPhotoSyncRepository(context, object : PhotoSyncRepository by base {
            override suspend fun uploadStagedMedia(folderId: String, uploadUri: Uri, sourceUri: Uri,
                displayName: String, mimeType: String, cleanupPolicy: PhotoCleanupPolicy?): Boolean {
                uploads.incrementAndGet(); uploaded.countDown(); return true
            }
        })
    }
    private suspend fun enqueue(repository: PhotoSyncRepository) {
        val uri = Uri.fromFile(File(context.cacheDir, "source.jpg").apply { writeText("media") })
        assertTrue(repository.enqueueMedia("folder-1", listOf(uri)))
    }
    @Test fun freshAndUpgradedInstallsDefaultToWifiAndWorkerConstraintIsSafe() {
        assertEquals(SyncNetworkPolicy.WifiOnly, PreferencesStore(context).getSyncNetworkPolicy())
        context.getSharedPreferences("photosync_preferences", 0).edit().putString("server_url", "https://photosync.bacus.dev").commit()
        assertEquals(SyncNetworkPolicy.WifiOnly, PreferencesStore(context).getSyncNetworkPolicy())
        assertEquals(NetworkType.UNMETERED, OfflineSyncScheduler.requiredNetworkType(context))
        assertFalse(AndroidSyncNetworkAccess(context).isAllowed())
    }
    @Test fun cellularCanStageButForegroundRetryRestoreAndGoogleCannotUpload() = runBlocking {
        val repository = repository()
        enqueue(repository)
        assertEquals(0, uploads.get())
        assertTrue(repository.observeMediaBatchProgress("folder-1").first().waitingForWifi)
        repository.refresh()
        repository.syncQueuedUploadsOnce()
        repository.signInWithGoogle("test")
        val restored = repository()
        restored.refresh()
        assertEquals(0, uploads.get())
        assertEquals(1, restored.observeStats().first().pendingPhotos)
    }
    @Test fun enablingMobilePersistsAndDisablingPreventsSubsequentUploads() = runBlocking {
        val repository = repository()
        enqueue(repository)
        repository.updateMobileSyncEnabled(true)
        assertEquals(1, uploads.get())
        assertEquals(SyncNetworkPolicy.AnyNetwork, PreferencesStore(context).getSyncNetworkPolicy())
        assertEquals(NetworkType.CONNECTED, OfflineSyncScheduler.requiredNetworkType(context))
        repository.updateMobileSyncEnabled(false)
        enqueue(repository)
        repository.syncQueuedUploadsOnce()
        assertEquals(1, uploads.get())
        assertEquals(NetworkType.UNMETERED, OfflineSyncScheduler.requiredNetworkType(context))
        assertEquals(SyncNetworkPolicy.WifiOnly, PreferencesStore(context).getSyncNetworkPolicy())
    }
    @Test fun observerAutomaticallyResumesWhenValidatedWifiArrives() = runBlocking {
        val repository = repository()
        enqueue(repository)
        val observer = NetworkSyncObserver(context, repository)
        observer.start()
        try {
            network(true)
            shadowOf(manager).networkCallbacks.forEach {
                it.onCapabilitiesChanged(manager.activeNetwork!!, manager.getNetworkCapabilities(manager.activeNetwork)!!)
            }
            assertTrue(uploaded.await(10, TimeUnit.SECONDS))
            assertEquals(1, uploads.get())
        } finally { observer.stop() }
    }
    @Test fun observerCellularCallbackDoesNotTriggerMediaOrMetadataRefresh() {
        val calls = AtomicInteger()
        val base = FakePhotoSyncRepository()
        val repository = OfflineFirstPhotoSyncRepository(context, object : PhotoSyncRepository by base {
            override suspend fun refresh() { calls.incrementAndGet() }
        })
        runBlocking { repository.onSyncNetworkChanged() }
        assertEquals(0, calls.get())
    }
    @Test fun handoverAndOptOutInvalidateAnAcquiredLease() {
        network(true)
        val access = AndroidSyncNetworkAccess(context)
        val lease = access.acquire()
        assertEquals(NetworkTransport.WiFi, lease.state.transport)
        assertFalse(lease.state.metered)
        network(false)
        assertTrue(runCatching { lease.checkAllowed() }.exceptionOrNull() is SyncNetworkPausedException)
        PreferencesStore(context).updateSyncNetworkPolicy(SyncNetworkPolicy.AnyNetwork)
        val mobileLease = access.acquire()
        assertEquals(NetworkTransport.Cellular, mobileLease.state.transport)
        assertTrue(mobileLease.state.metered)
        PreferencesStore(context).updateSyncNetworkPolicy(SyncNetworkPolicy.WifiOnly)
        assertTrue(runCatching { mobileLease.checkAllowed() }.exceptionOrNull() is SyncNetworkPausedException)
    }
    @Test fun meteredWifiAllowedButUnvalidatedAndUnmeteredCellularAreNotWifi() {
        val access = AndroidSyncNetworkAccess(context)
        network(true, metered = true); assertTrue(access.isAllowed())
        network(true, validated = false); assertFalse(access.isAllowed())
        network(false, metered = false); assertFalse(access.isAllowed())
        assertFalse(SyncNetworkState(NetworkTransport.Other, true, false).allows(SyncNetworkPolicy.WifiOnly))
    }
    @Test fun policyPauseRemainsPendingWithoutFailureOrBackoff() = runBlocking {
        network(true)
        val base = FakePhotoSyncRepository(seedFolders = listOf(FolderRecord("folder-1", "Camera", emptyList())))
        val repository = OfflineFirstPhotoSyncRepository(context, object : PhotoSyncRepository by base {
            override suspend fun uploadStagedMedia(folderId: String, uploadUri: Uri, sourceUri: Uri,
                displayName: String, mimeType: String, cleanupPolicy: PhotoCleanupPolicy?): Boolean = throw SyncNetworkPausedException()
        })
        enqueue(repository)
        val item = JSONArray(context.getSharedPreferences("photosync_offline_queue_v1", 0).getString("items", "[]")).getJSONObject(0)
        assertFalse(item.getBoolean("terminal_failure"))
        assertEquals(0, item.getInt("retry_attempts"))
        assertEquals(0L, item.getLong("next_attempt_at"))
    }
}

package com.photosync.android.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.work.Configuration
import androidx.work.WorkManager
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.model.PhotoItem
import com.photosync.android.domain.model.PhotoSyncStatus
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
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LegacyRetryPathTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { runCatching { WorkManager.initialize(context, Configuration.Builder().build()) } }
    @Test fun legacyPendingFailedAndUploadingAreAdoptedOnceAndRetryBackoffHasOneOwner() = runBlocking {
        val source = File(context.cacheDir, "legacy.mp4").apply { writeText("media") }
        val photos = listOf(PhotoSyncStatus.Pending, PhotoSyncStatus.Failed, PhotoSyncStatus.Uploading).mapIndexed { i, status ->
            PhotoItem("old-$i", "legacy.mp4", status, Uri.fromFile(source).toString())
        }
        val base = FakePhotoSyncRepository(seedFolders = listOf(FolderRecord("folder-1", "Camera", photos)))
        var transfers = 0
        val delegate = object : PhotoSyncRepository by base {
            override suspend fun uploadStagedMedia(folderId: String, uploadUri: Uri, sourceUri: Uri,
                displayName: String, mimeType: String, cleanupPolicy: PhotoCleanupPolicy?): Boolean {
                assertNotEquals(sourceUri, uploadUri)
                assertEquals(PhotoCleanupPolicy.Keep, cleanupPolicy)
                transfers++
                throw PhotoSyncApiException(503, "UNAVAILABLE", message = "temporary")
            }
            override suspend fun uploadToFolder(folderId: String, uri: Uri): Boolean = error("Independent legacy transfer is forbidden")
        }
        val repository = OfflineFirstPhotoSyncRepository(context, delegate, { true })
        repeat(3) { repository.refresh() }
        assertEquals(1, transfers)
        val queue = JSONArray(context.getSharedPreferences("photosync_offline_queue_v1", 0).getString("items", "[]"))
        assertEquals(1, queue.length())
        OfflineFirstPhotoSyncRepository(context, delegate, { true }).refresh()
        assertEquals(1, transfers)
    }
    @Test fun removingOwnedQueueItemCannotResurrectItViaLegacyAdoption() = runBlocking {
        val source = File(context.cacheDir, "cancelled.mp4").apply { writeText("media") }
        val old = PhotoItem("old", "cancelled.mp4", PhotoSyncStatus.Failed, Uri.fromFile(source).toString())
        val base = FakePhotoSyncRepository(seedFolders = listOf(FolderRecord("folder-1", "Camera", listOf(old))))
        val repository = OfflineFirstPhotoSyncRepository(context, base, { false })
        repository.enqueueMedia("folder-1", listOf(Uri.fromFile(source)))
        assertEquals(1, repository.observeStats().first().pendingPhotos)
        assertEquals(0, repository.observeStats().first().failedPhotos)
        repository.deletePhoto("folder-1", repository.observeFolder("folder-1").first()!!.photos.single().id)
        repository.refresh()
        assertTrue(repository.observeFolder("folder-1").first()!!.photos.isEmpty())
        assertTrue(source.exists())
    }

    @Test fun legacyUnreadableRecordBecomesVisibleTerminalFailureWithoutRetryingOrDeletingSource() = runBlocking {
        val photo = PhotoItem("old", "missing.mp4", PhotoSyncStatus.Failed, "file:///missing.mp4")
        val base = FakePhotoSyncRepository(seedFolders = listOf(FolderRecord("folder-1", "Camera", listOf(photo))))
        val repository = OfflineFirstPhotoSyncRepository(context, base, { false })
        repository.refresh()
        repository.refresh()
        assertTrue(base.observeFolder("folder-1").first()!!.photos.isEmpty())
        val restored = OfflineFirstPhotoSyncRepository(context, base, { false })
        assertEquals(PhotoSyncStatus.Failed, restored.observeFolder("folder-1").first()!!.photos.single().status)
    }
}

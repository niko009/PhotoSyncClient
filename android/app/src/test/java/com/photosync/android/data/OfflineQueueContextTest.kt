package com.photosync.android.data

import android.app.Application
import android.content.Context
import android.net.Uri
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.model.PhotoItem
import com.photosync.android.domain.model.PhotoSyncStatus
import com.photosync.android.domain.repository.PhotoSyncRepository
import androidx.work.Configuration
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class OfflineQueueContextTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        try { WorkManager.initialize(context, Configuration.Builder().build()) }
        catch (_: IllegalStateException) { /* already initialized by Startup */ }
        context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE).edit()
            .putString("items", """[{"id":"pending","folder_id":"folder","title":"video.mp4","mime_type":"video/mp4","source_uri":"file:///source.mp4","staged_uri":"file:///staged.mp4"}]""")
            .commit()
    }

    @Test
    fun serverCannotChangeWhileAnUploadIsPending() = runBlocking {
        val delegate = FakePhotoSyncRepository()
        val repository = OfflineFirstPhotoSyncRepository(context, delegate)
        val before = delegate.observeServerUrl().first()
        assertTrue(runCatching { repository.updateServerUrl("https://different.example") }.exceptionOrNull() is IllegalStateException)
        assertEquals(before, delegate.observeServerUrl().first())
        assertTrue(context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE)
            .getString("items", "")!!.contains("pending"))
    }

    @Test
    fun freshDeviceCanLinkAccountWhileAnUploadIsPending() = runBlocking {
        val delegate = FakePhotoSyncRepository()
        val repository = OfflineFirstPhotoSyncRepository(context, delegate)

        repository.signInWithGoogle("test")

        assertNotNull(delegate.observeGoogleAccount().first())
    }

    @Test
    fun linkedAccountCannotChangeWhileAnUploadIsPending() = runBlocking {
        val delegate = FakePhotoSyncRepository().also { it.signInWithGoogle("existing") }
        val repository = OfflineFirstPhotoSyncRepository(context, delegate)

        assertTrue(runCatching { repository.signInWithGoogle("different") }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun sharedMediaIsCopiedIntoDurableBatchBeforeReturning() = runBlocking {
        context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE)
            .edit().remove("items").commit()
        val source = File(context.cacheDir, "shared-photo.jpg").apply { writeText("photo") }
        val repository = OfflineFirstPhotoSyncRepository(context, FakePhotoSyncRepository(uploadSucceeds = false))

        assertTrue(repository.enqueueSharedMedia("folder-1", listOf(Uri.fromFile(source))))

        val queueJson = context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE)
            .getString("items", "").orEmpty()
        assertTrue(queueJson.contains("share_batch_id"))
        assertTrue(File(context.filesDir, "offline_queue/folder-1").listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun queuedMediaKeepsTheCleanupPolicySelectedWhenItWasAdded() = runBlocking {
        context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE)
            .edit().remove("items").commit()
        val source = File(context.cacheDir, "policy-snapshot.jpg").apply { writeText("photo") }
        val delegate = FakePhotoSyncRepository(uploadSucceeds = false)
        val repository = OfflineFirstPhotoSyncRepository(context, delegate)

        assertTrue(repository.uploadToFolder("folder-1", Uri.fromFile(source)))
        delegate.updateGlobalPhotoCleanupPolicy(com.photosync.android.domain.model.PhotoCleanupPolicy.Delete)

        val queueJson = context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE)
            .getString("items", "").orEmpty()
        assertTrue(queueJson.contains("\"cleanup_policy\":\"Keep\""))
    }

    @Test
    fun folderKeepOverrideWinsOverGlobalDelete() = runBlocking {
        context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE)
            .edit().remove("items").commit()
        val source = File(context.cacheDir, "folder-keep.jpg").apply { writeText("photo") }
        val delegate = FakePhotoSyncRepository().also {
            it.updateGlobalPhotoCleanupPolicy(PhotoCleanupPolicy.Delete)
            it.updateFolderPhotoCleanupPolicy("folder-1", PhotoCleanupPolicy.Keep)
        }
        val repository = OfflineFirstPhotoSyncRepository(context, delegate, networkAvailable = { false })

        assertTrue(repository.uploadToFolder("folder-1", Uri.fromFile(source)))

        val queueJson = context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE)
            .getString("items", "").orEmpty()
        assertTrue(queueJson.contains("\"cleanup_policy\":\"Keep\""))
        assertTrue(source.exists())
    }

    @Test
    fun folderDeleteOverrideWinsOverGlobalKeep() = runBlocking {
        context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE)
            .edit().remove("items").commit()
        val source = File(context.cacheDir, "folder-delete.jpg").apply { writeText("photo") }
        val delegate = FakePhotoSyncRepository().also {
            it.updateGlobalPhotoCleanupPolicy(PhotoCleanupPolicy.Keep)
            it.updateFolderPhotoCleanupPolicy("folder-1", PhotoCleanupPolicy.Delete)
        }
        val repository = OfflineFirstPhotoSyncRepository(context, delegate, networkAvailable = { false })

        assertTrue(repository.uploadToFolder("folder-1", Uri.fromFile(source)))

        val queueJson = context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE)
            .getString("items", "").orEmpty()
        assertTrue(queueJson.contains("\"cleanup_policy\":\"Delete\""))
    }

    @Test
    fun successfulQueuedUploadPassesOriginalSourceInsteadOfStagedCopyToCleanup() = runBlocking {
        context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE)
            .edit().remove("items").commit()
        val source = File(context.cacheDir, "source-for-delete.jpg").apply { writeText("photo") }
        val base = FakePhotoSyncRepository()
        base.updateGlobalPhotoCleanupPolicy(PhotoCleanupPolicy.Delete)
        var verified = false
        val delegate = object : PhotoSyncRepository by base {
            override suspend fun uploadStagedMedia(
                folderId: String,
                uploadUri: Uri,
                sourceUri: Uri,
                displayName: String,
                mimeType: String,
                cleanupPolicy: PhotoCleanupPolicy?,
            ): Boolean {
                assertEquals(Uri.fromFile(source), sourceUri)
                assertNotEquals(sourceUri, uploadUri)
                assertEquals(PhotoCleanupPolicy.Delete, cleanupPolicy)
                verified = true
                return true
            }
        }
        val repository = OfflineFirstPhotoSyncRepository(context, delegate, networkAvailable = { true })

        assertTrue(repository.uploadToFolder("folder-1", Uri.fromFile(source)))

        assertTrue(verified)
    }

    @Test
    fun oldSerializedQueueItemUsesCurrentUploadPathOnlyOnce() = runBlocking {
        context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE).edit()
            .putString("items", """[{"id":"old-item","folder_id":"folder-1","title":"old-video.mp4","mime_type":"video/mp4","local_uri":"file:///old-video.mp4","upload_type":"multipart"}]""")
            .commit()
        val base = FakePhotoSyncRepository()
        var uploads = 0
        val delegate = object : PhotoSyncRepository by base {
            override suspend fun uploadStagedMedia(
                folderId: String,
                uploadUri: Uri,
                sourceUri: Uri,
                displayName: String,
                mimeType: String,
                cleanupPolicy: PhotoCleanupPolicy?,
            ): Boolean {
                uploads++
                assertEquals("file:///old-video.mp4", uploadUri.toString())
                assertEquals(PhotoCleanupPolicy.Keep, cleanupPolicy)
                return true
            }
        }
        val repository = OfflineFirstPhotoSyncRepository(context, delegate, networkAvailable = { true })
        val before = base.observeFolders().first().first { it.id == "folder-1" }.photoCount

        assertEquals(before + 1, repository.observeFolders().first().first { it.id == "folder-1" }.photoCount)
        repository.syncQueuedUploadsOnce()
        repository.syncQueuedUploadsOnce()

        assertEquals(1, uploads)
        assertEquals(before, repository.observeFolders().first().first { it.id == "folder-1" }.photoCount)
        assertEquals("[]", context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE)
            .getString("items", null))
    }

    @Test
    fun differentMediaWithSameFilenameIsNotHiddenByQueue() = runBlocking {
        context.getSharedPreferences("photosync_offline_queue_v1", Context.MODE_PRIVATE).edit()
            .putString("items", """[{"id":"queued","folder_id":"folder-1","title":"same.jpg","mime_type":"image/jpeg","local_uri":"file:///queued.jpg"}]""")
            .commit()
        val other = PhotoItem("other", "same.jpg", PhotoSyncStatus.Pending, localUri = "file:///other.jpg")
        val delegate = FakePhotoSyncRepository(seedFolders = listOf(FolderRecord("folder-1", "Camera", listOf(other))))
        val repository = OfflineFirstPhotoSyncRepository(context, delegate, networkAvailable = { false })

        assertEquals(2, repository.observeFolder("folder-1").first()!!.photos.size)
    }
}

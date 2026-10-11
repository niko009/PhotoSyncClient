package com.photosync.android.data

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkManager
import com.photosync.android.domain.model.MediaBatchPhase
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BatchEnqueueTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("photosync_offline_queue_v1", 0)
    private fun items() = JSONArray(prefs.getString("items", "[]"))
    @Before fun setup() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        prefs.edit().clear().commit()
    }
    private fun media(name: String) = Uri.fromFile(File(context.cacheDir, name).apply { writeText(name) })
    private fun delegate(upload: suspend (Uri) -> Boolean): PhotoSyncRepository {
        val base = FakePhotoSyncRepository(seedFolders = listOf(FolderRecord("folder-1", "Camera", emptyList())))
        return object : PhotoSyncRepository by base {
            override suspend fun uploadStagedMedia(folderId: String, uploadUri: Uri, sourceUri: Uri,
                displayName: String, mimeType: String, cleanupPolicy: PhotoCleanupPolicy?): Boolean = upload(uploadUri)
        }
    }

    @Test fun completeBatchIsCommittedBeforeFirstTransferAndDuplicatesAreCollapsed() = runBlocking {
        val uris = (1..15).map { media("$it.jpg") }
        var uploads = 0
        val repository = OfflineFirstPhotoSyncRepository(context, delegate {
            if (uploads == 0) {
                assertEquals(15, items().length())
                for (i in 0 until 15) assertTrue(File(Uri.parse(items().getJSONObject(i).getString("staged_uri")).path!!).exists())
                assertFalse(prefs.contains("preparing"))
            }
            uploads++
            true
        }, networkAvailable = { true })
        assertTrue(repository.enqueueMedia("folder-1", uris + uris))
        assertEquals(15, uploads)
        assertEquals(0, items().length())
        assertEquals(MediaBatchPhase.Completed, repository.observeMediaBatchProgress("folder-1").first().phase)
    }

    @Test fun workerCannotPassPreparationBarrierAndStagingIsOffMainThread() = runBlocking {
        val copying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uploads = AtomicInteger()
        val uri = Uri.parse("content://batch/blocked.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
            var read = false
            override fun read(): Int {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                if (read) return -1
                read = true
                copying.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return 1
            }
        })
        val repository = OfflineFirstPhotoSyncRepository(context, delegate { uploads.incrementAndGet(); true }, { true })
        val prepare = async(Dispatchers.IO) { repository.enqueueMedia("folder-1", listOf(media("first.jpg"), uri)) }
        assertTrue(copying.await(10, TimeUnit.SECONDS))
        val worker = async(Dispatchers.IO) { repository.syncQueuedUploadsOnce() }
        try {
            assertEquals(MediaBatchPhase.Preparing, repository.observeMediaBatchProgress("folder-1").first().phase)
            assertEquals(0, items().length())
            assertEquals(0, uploads.get())
            assertFalse(worker.isCompleted)
        } finally { release.countDown() }
        assertTrue(prepare.await())
        worker.await()
        assertEquals(2, uploads.get())
    }

    @Test fun largeBatchStreamsOneSourceAtATimeUsingBoundedBuffers() = runBlocking {
        val open = AtomicInteger()
        val count = 500
        val uris = (1..count).map { n ->
            Uri.parse("content://batch/$n.jpg").also { uri ->
                shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
                    var remaining = 32 * 1024
                    var started = false
                    override fun read(): Int = error("Use bounded streaming reads")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (!started) { started = true; assertEquals(1, open.incrementAndGet()) }
                        assertTrue(buffer.size <= 64 * 1024)
                        if (remaining == 0) return -1
                        val read = minOf(length, remaining)
                        buffer.fill(1, offset, offset + read)
                        remaining -= read
                        return read
                    }
                    override fun close() { open.decrementAndGet() }
                })
            }
        }
        val repository = OfflineFirstPhotoSyncRepository(context, delegate { error("Must remain offline") }, { false })
        assertTrue(repository.enqueueMedia("folder-1", uris))
        assertEquals(count, items().length())
        assertEquals(0, open.get())
        val restored = OfflineFirstPhotoSyncRepository(context, delegate { error("Must remain offline") }, { false })
        assertEquals(count, restored.observeFolder("folder-1").first()!!.photos.size)
    }

    @Test fun failedAtomicPreparationRollsBackNewFilesAndRetainsExistingQueue() = runBlocking {
        val repository = OfflineFirstPhotoSyncRepository(context, delegate { error("offline") }, { false })
        assertTrue(repository.enqueueMedia("folder-1", listOf(media("old.jpg"))))
        val before = prefs.getString("items", "")
        assertFalse(repository.enqueueMedia("folder-1", listOf(media("good.jpg"), Uri.parse("file:///absent.jpg"))))
        assertEquals(before, prefs.getString("items", ""))
        assertEquals(1, File(context.filesDir, "offline_queue").walk().count { it.isFile })
        assertEquals(MediaBatchPhase.Failed, repository.observeMediaBatchProgress("folder-1").first().phase)
        val restarted = OfflineFirstPhotoSyncRepository(context, delegate { false }, { false })
        assertEquals(MediaBatchPhase.Failed, restarted.observeMediaBatchProgress("folder-1").first().phase)
    }

    @Test fun processDeathDuringPreparationCleansUnpublishedFilesAndReportsFailure() {
        val directory = File(context.filesDir, "offline_queue/folder-1/interrupted").apply { mkdirs() }
        File(directory, "partial.jpg").writeText("partial")
        prefs.edit().putString("preparing", JSONObject().put("folder", "folder-1")
            .put("total", 500).put("directory", directory.absolutePath).toString()).commit()
        val repository = OfflineFirstPhotoSyncRepository(context, delegate { false }, { false })
        assertFalse(directory.exists())
        assertEquals(0, items().length())
        runBlocking { assertEquals(MediaBatchPhase.Failed, repository.observeMediaBatchProgress("folder-1").first().phase) }
    }

    @Test fun processDeathAfterQueueCommitDoesNotDeleteCommittedFiles() = runBlocking {
        val repository = OfflineFirstPhotoSyncRepository(context, delegate { false }, { false })
        repository.enqueueMedia("folder-1", listOf(media("safe.jpg")))
        val file = File(Uri.parse(items().getJSONObject(0).getString("staged_uri")).path!!)
        prefs.edit().putString("preparing", JSONObject().put("folder", "folder-1")
            .put("total", 1).put("directory", file.parent).toString()).commit()
        OfflineFirstPhotoSyncRepository(context, delegate { false }, { false })
        assertTrue(file.exists())
        assertEquals(1, items().length())
    }
}

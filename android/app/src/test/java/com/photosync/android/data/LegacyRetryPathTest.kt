package com.photosync.android.data

import android.app.Application
import android.net.Uri
import com.photosync.android.domain.model.ConnectionStatus
import com.photosync.android.domain.model.DashboardStats
import com.photosync.android.domain.model.PhotoItem
import com.photosync.android.domain.model.PhotoSyncStatus
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LegacyRetryPathTest {
    @Test fun oldFailedRecordUsesCurrentRepositoryUploadOnce() = runBlocking {
        val old = PhotoItem("old", "video.mp4", PhotoSyncStatus.Failed, localUri = "file:///video.mp4")
        val base = FakePhotoSyncRepository(seedFolders = listOf(FolderRecord("folder-1", "Camera", listOf(old))))
        var uploads = 0
        val delegate = object : PhotoSyncRepository by base {
            override fun observeStats(): Flow<DashboardStats> = flowOf(
                DashboardStats(connectionStatus = ConnectionStatus.Online))
            override suspend fun uploadToFolder(folderId: String, uri: Uri): Boolean {
                assertEquals("folder-1", folderId)
                assertEquals("file:///video.mp4", uri.toString())
                uploads++
                return true
            }
        }
        val repository = RetryingPhotoSyncRepository(delegate)

        repository.refresh()
        repository.refresh()

        assertEquals(1, uploads)
        assertTrue(base.observeFolder("folder-1").first()!!.photos.isEmpty())
    }
}

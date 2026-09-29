package com.photosync.android.data

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaCleanupManagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("photosync_media_cleanup_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun contentDeletionIsPersistedUntilCompleted() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val manager = MediaCleanupManager(context)

        manager.requestDelete(uri)

        assertEquals(listOf(uri), manager.pendingDeletionUris.value)
        assertEquals(listOf(uri), MediaCleanupManager(context).pendingDeletionUris.value)

        manager.complete(uri)
        assertEquals(emptyList<Uri>(), manager.pendingDeletionUris.value)
    }

    @Test
    fun stagedFileIsDeletedWithoutAConfirmationQueue() {
        val file = File(context.cacheDir, "cleanup-test.jpg").apply { writeText("fixture") }
        val manager = MediaCleanupManager(context)

        manager.requestDelete(Uri.fromFile(file))

        assertFalse(file.exists())
        assertEquals(emptyList<Uri>(), manager.pendingDeletionUris.value)
    }

    @Test
    fun localPhotoPickerUriQueuesTheSourceMediaStoreItem() {
        val pickerUri = Uri.parse(
            "content://media/picker/0/com.android.providers.media.photopicker/media/42",
        )
        val manager = MediaCleanupManager(context)

        manager.requestDelete(pickerUri)

        assertEquals(
            listOf(Uri.parse("content://media/external/file/42")),
            manager.pendingDeletionUris.value,
        )
    }

    @Test
    fun legacyLocalPhotoPickerUriQueuesTheSourceMediaStoreItem() {
        val manager = MediaCleanupManager(context)

        manager.requestDelete(Uri.parse("content://media/picker/0/73"))

        assertEquals(
            listOf(Uri.parse("content://media/external/file/73")),
            manager.pendingDeletionUris.value,
        )
    }

    @Test
    fun cloudPhotoPickerUriDoesNotQueueAnUnrelatedLocalDeletion() {
        val manager = MediaCleanupManager(context)

        manager.requestDelete(Uri.parse("content://media/picker/0/cloud.example/media/cloud-id"))

        assertEquals(emptyList<Uri>(), manager.pendingDeletionUris.value)
    }

    @Test
    fun cancelledDeleteConfirmationOnlyRemovesThePendingRequest() {
        val sourceUri = Uri.parse("content://media/external/images/media/91")
        val manager = MediaCleanupManager(context)
        manager.requestDelete(sourceUri)

        // MediaDeletionEffect calls complete for a cancelled system dialog; Android has not
        // deleted the item, and cleanup completion has no connection to upload success state.
        manager.complete(sourceUri)

        assertEquals(emptyList<Uri>(), manager.pendingDeletionUris.value)
    }
}

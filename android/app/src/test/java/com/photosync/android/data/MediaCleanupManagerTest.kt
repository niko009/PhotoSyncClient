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
import java.io.File

@RunWith(RobolectricTestRunner::class)
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
}

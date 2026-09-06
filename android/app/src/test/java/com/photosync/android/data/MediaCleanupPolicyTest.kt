package com.photosync.android.data

import com.photosync.android.domain.model.PhotoCleanupPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCleanupPolicyTest {
    @Test
    fun keepAlwaysPreservesSource() {
        assertEquals(MediaCleanupAction.KeepSource, cleanupAction(PhotoCleanupPolicy.Keep, "image/jpeg"))
    }

    @Test
    fun compressImageCreatesReplacementAndDeletesSource() {
        assertEquals(
            MediaCleanupAction.CompressImageThenDeleteSource,
            cleanupAction(PhotoCleanupPolicy.Compress, "image/png"),
        )
    }

    @Test
    fun compressVideoConservativelyPreservesSource() {
        assertEquals(MediaCleanupAction.KeepSource, cleanupAction(PhotoCleanupPolicy.Compress, "video/mp4"))
    }

    @Test
    fun deleteRequestsSourceRemoval() {
        assertEquals(MediaCleanupAction.DeleteSource, cleanupAction(PhotoCleanupPolicy.Delete, "image/jpeg"))
    }
}

package com.photosync.android.data

import android.app.Application
import android.content.Context
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
    fun accountCannotChangeWhileAnUploadIsPending() = runBlocking {
        val delegate = FakePhotoSyncRepository()
        val repository = OfflineFirstPhotoSyncRepository(context, delegate)
        assertTrue(runCatching { repository.signInWithGoogle("test") }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { repository.signOutFromGoogle() }.exceptionOrNull() is IllegalStateException)
        assertNull(delegate.observeGoogleAccount().first())
    }
}

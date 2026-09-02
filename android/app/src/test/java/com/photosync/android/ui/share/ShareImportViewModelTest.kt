package com.photosync.android.ui.share

import android.net.Uri
import com.photosync.android.MainDispatcherRule
import com.photosync.android.data.FakePhotoSyncRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareImportViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun successfulBatchReportsEveryUploadedItem() = runTest {
        val viewModel = ShareImportViewModel(FakePhotoSyncRepository())
        advanceUntilIdle()

        viewModel.setSharedUris(listOf(Uri.parse("content://gallery/one"), Uri.parse("content://gallery/two")))
        viewModel.importToSelectedFolder()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isFinished)
        assertFalse(viewModel.state.value.isUploading)
        assertEquals(2, viewModel.state.value.processedCount)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun failedUploadDoesNotReportBatchAsFinished() = runTest {
        val viewModel = ShareImportViewModel(FakePhotoSyncRepository(uploadSucceeds = false))
        advanceUntilIdle()

        viewModel.setSharedUris(listOf(Uri.parse("content://gallery/one")))
        viewModel.importToSelectedFolder()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isFinished)
        assertFalse(viewModel.state.value.isUploading)
        assertEquals(0, viewModel.state.value.processedCount)
        assertEquals("Import failed.", viewModel.state.value.errorMessage)
    }
}

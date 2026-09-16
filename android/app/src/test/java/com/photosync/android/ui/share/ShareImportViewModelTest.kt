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
    fun successfulBatchIsQueuedAfterExplicitFolderSelection() = runTest {
        val viewModel = ShareImportViewModel(FakePhotoSyncRepository())
        advanceUntilIdle()

        viewModel.setSharedUris(listOf(Uri.parse("content://gallery/one"), Uri.parse("content://gallery/two")))
        assertEquals(null, viewModel.state.value.selectedFolderId)
        viewModel.selectFolder("folder-1")
        viewModel.enqueueToSelectedFolder()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isQueued)
        assertFalse(viewModel.state.value.isQueueing)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun failedUploadDoesNotReportBatchAsFinished() = runTest {
        val viewModel = ShareImportViewModel(FakePhotoSyncRepository(uploadSucceeds = false))
        advanceUntilIdle()

        viewModel.setSharedUris(listOf(Uri.parse("content://gallery/one")))
        viewModel.selectFolder("folder-1")
        viewModel.enqueueToSelectedFolder()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isQueued)
        assertFalse(viewModel.state.value.isQueueing)
        assertEquals("Could not queue the selected media.", viewModel.state.value.errorMessage)
    }

    @Test
    fun newFolderCanBeCreatedAndIsSelectedForThisShare() = runTest {
        val viewModel = ShareImportViewModel(FakePhotoSyncRepository())
        advanceUntilIdle()

        viewModel.updateNewFolderName("Redmi trip")
        viewModel.createFolder()
        advanceUntilIdle()

        val created = viewModel.state.value.folders.single { it.name == "Redmi trip" }
        assertEquals(created.id, viewModel.state.value.selectedFolderId)
        assertEquals("", viewModel.state.value.newFolderName)
        assertFalse(viewModel.state.value.isCreatingFolder)
    }
}

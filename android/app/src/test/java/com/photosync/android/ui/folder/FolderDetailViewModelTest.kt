package com.photosync.android.ui.folder

import com.photosync.android.MainDispatcherRule
import com.photosync.android.data.FakePhotoSyncRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FolderDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun returnsFolderDetailsForExistingFolder() = runTest {
        val viewModel = FolderDetailViewModel(
            folderId = "folder-2",
            repository = FakePhotoSyncRepository(),
        )

        advanceUntilIdle()

        assertEquals("Vacation 2026", viewModel.state.value.folder?.name)
        assertEquals(8, viewModel.state.value.folder?.photos?.size)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun exposesErrorForUnknownFolder() = runTest {
        val viewModel = FolderDetailViewModel(
            folderId = "missing",
            repository = FakePhotoSyncRepository(),
        )

        advanceUntilIdle()

        assertEquals("Folder not found.", viewModel.state.value.errorMessage)
        assertNull(viewModel.state.value.folder)
    }
}

package com.photosync.android.ui.home

import com.photosync.android.MainDispatcherRule
import com.photosync.android.data.FakePhotoSyncRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun exposesSeededStatsAndFolders() = runTest {
        val viewModel = HomeViewModel(FakePhotoSyncRepository())

        advanceUntilIdle()

        assertEquals("http://127.0.0.1:5187", viewModel.state.value.serverUrl)
        assertEquals(3, viewModel.state.value.stats.totalFolders)
        assertEquals(18, viewModel.state.value.stats.totalPhotos)
        assertEquals("Camera Roll", viewModel.state.value.folders.first().name)
    }

    @Test
    fun addFolderUpdatesDashboardState() = runTest {
        val viewModel = HomeViewModel(FakePhotoSyncRepository())

        advanceUntilIdle()
        viewModel.addFolder("Folder 4")
        advanceUntilIdle()

        assertEquals(4, viewModel.state.value.stats.totalFolders)
        assertEquals("Folder 4", viewModel.state.value.folders.last().name)
        assertEquals(22, viewModel.state.value.stats.totalPhotos)
    }

    @Test
    fun blankFolderNameIsIgnored() = runTest {
        val viewModel = HomeViewModel(FakePhotoSyncRepository())

        advanceUntilIdle()
        viewModel.addFolder("   ")
        advanceUntilIdle()

        assertEquals(3, viewModel.state.value.stats.totalFolders)
    }

    @Test
    fun updateServerUrlUpdatesUiState() = runTest {
        val viewModel = HomeViewModel(FakePhotoSyncRepository())

        advanceUntilIdle()
        viewModel.updateServerUrl("http://192.168.1.10:5187")
        advanceUntilIdle()

        assertEquals("http://192.168.1.10:5187", viewModel.state.value.serverUrl)
    }
}

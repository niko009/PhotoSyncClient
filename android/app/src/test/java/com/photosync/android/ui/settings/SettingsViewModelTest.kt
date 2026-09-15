package com.photosync.android.ui.settings

import com.photosync.android.MainDispatcherRule
import com.photosync.android.data.FakePhotoSyncRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun exposesDeviceIdentifiersForServerLogCorrelation() = runTest {
        val viewModel = SettingsViewModel(FakePhotoSyncRepository())

        advanceUntilIdle()

        assertEquals("Test Phone", viewModel.state.value.deviceIdentifiers.deviceName)
        assertEquals("test-device-uuid", viewModel.state.value.deviceIdentifiers.deviceUuid)
        assertEquals(42, viewModel.state.value.deviceIdentifiers.serverDeviceId)
    }
}

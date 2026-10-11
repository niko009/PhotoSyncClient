package com.photosync.android.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.photosync.android.domain.model.DeviceIdentifiers
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun mobileSyncIsOffAndRequiresAnExplicitToggle() {
        val state = mutableStateOf(SettingsUiState())
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(state.value, {}, {}, {}, {}, {}, {},
                    onMobileSyncChanged = { enabled -> state.value = state.value.copy(mobileSyncEnabled = enabled) })
            }
        }
        composeRule.onNodeWithTag("mobile_sync").performScrollTo().assertIsOff().performClick().assertIsOn()
        composeRule.onNodeWithTag("mobile_sync").performClick().assertIsOff()
    }

    @Test
    fun showsDeviceIdentifiersUsedByServerLogs() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        deviceIdentifiers = DeviceIdentifiers(
                            deviceUuid = "67f90f58-5638-4a5d-9d46-29a3584d12cf",
                            serverDeviceId = 73,
                            deviceName = "Bacus Test Phone",
                        ),
                    ),
                    onBack = {},
                    onSaveServerUrl = {},
                    onSaveGlobalPolicy = {},
                    onGoogleSignIn = {},
                    onGoogleSignOut = {},
                    onOpenFamily = {},
                )
            }
        }

        composeRule.onNodeWithTag("device_uuid")
            .performScrollTo()
            .assertTextContains("67f90f58-5638-4a5d-9d46-29a3584d12cf", substring = true)
        composeRule.onNodeWithTag("server_device_id")
            .assertTextContains("73", substring = true)
        composeRule.onNodeWithTag("device_name")
            .assertTextContains("Bacus Test Phone", substring = true)
    }
}

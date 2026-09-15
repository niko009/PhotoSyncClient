package com.photosync.android.ui.settings

import androidx.compose.material3.MaterialTheme
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
            .assertTextContains("67f90f58-5638-4a5d-9d46-29a3584d12cf")
        composeRule.onNodeWithTag("server_device_id")
            .assertTextContains("73")
        composeRule.onNodeWithTag("device_name")
            .assertTextContains("Bacus Test Phone")
    }
}

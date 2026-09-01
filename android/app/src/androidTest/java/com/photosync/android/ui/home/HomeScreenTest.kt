package com.photosync.android.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.photosync.android.domain.model.DashboardStats
import com.photosync.android.domain.model.FolderSummary
import com.photosync.android.ui.theme.PhotoSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersStatsAndFolderList() {
        composeRule.setContent {
            PhotoSyncTheme {
                HomeScreen(
                    state = HomeUiState(
                        serverUrl = "http://10.0.2.2:5187",
                        stats = DashboardStats(
                            totalFolders = 2,
                            totalPhotos = 14,
                            syncedPhotos = 10,
                            pendingPhotos = 3,
                            failedPhotos = 1,
                        ),
                        folders = listOf(
                            FolderSummary("1", "Camera Roll", 8, 6, 1, 1, "1 failed"),
                            FolderSummary("2", "Vacation", 6, 4, 2, 0, "2 pending"),
                        ),
                    ),
                    onAddFolder = {},
                    onRefresh = {},
                    onOpenSettings = {},
                    onFolderClick = {},
                )
            }
        }

        composeRule.onNodeWithText("PhotoSync").assertIsDisplayed()
        composeRule.onNodeWithText("Vacation").assertIsDisplayed()
        composeRule.onAllNodesWithTag("folder_card").assertCountEquals(2)
    }

    @Test
    fun addFolderButtonInvokesCallback() {
        var clickCount = 0

        composeRule.setContent {
            PhotoSyncTheme {
                HomeScreen(
                    state = HomeUiState(),
                    onAddFolder = { clickCount++ },
                    onRefresh = {},
                    onOpenSettings = {},
                    onFolderClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("home_add_folder").performClick()
        assertEquals(0, clickCount)
        composeRule.onNodeWithTag("folder_name_input").performTextInput("Family")
        composeRule.onNodeWithText("Create", useUnmergedTree = true).performClick()

        assertEquals(1, clickCount)
    }
}

package com.photosync.android.ui.folder

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import com.photosync.android.domain.model.FolderDetail
import com.photosync.android.domain.model.PhotoItem
import com.photosync.android.domain.model.PhotoSyncStatus
import com.photosync.android.ui.theme.PhotoSyncTheme
import org.junit.Rule
import org.junit.Test

class FolderDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersSquareGridStatuses() {
        composeRule.setContent {
            PhotoSyncTheme {
                FolderDetailScreen(
                    state = FolderDetailUiState(
                        folder = FolderDetail(
                            id = "folder-1",
                            name = "Camera Roll",
                            photos = listOf(
                                PhotoItem("1", "IMG_11", PhotoSyncStatus.Synced),
                                PhotoItem("2", "IMG_12", PhotoSyncStatus.Pending),
                                PhotoItem("3", "IMG_13", PhotoSyncStatus.Uploading),
                                PhotoItem("4", "IMG_14", PhotoSyncStatus.Failed),
                            ),
                        ),
                    ),
                    onBack = {},
                    onAddMedia = {},
                    onDeletePhoto = {},
                    onDownloadPhoto = {},
                    onUpdateCleanupPolicy = {},
                )
            }
        }

        composeRule.onNodeWithText("Camera Roll").assertIsDisplayed()
        composeRule.onAllNodesWithTag("photo_cell").assertCountEquals(4)
        composeRule.onAllNodesWithTag("photo_status").assertCountEquals(4)
    }
}

package com.photosync.android.ui.folder

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.photosync.android.R
import com.photosync.android.domain.model.MediaBatchProgress
import com.photosync.android.domain.model.MediaBatchPhase
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

    @Test fun preparationWaitingSyncFailureAndCompletionHaveDistinctLabels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val batch = mutableStateOf(MediaBatchProgress("folder-1", MediaBatchPhase.Preparing, prepared = 37, total = 500))
        composeRule.setContent {
            PhotoSyncTheme {
                FolderDetailScreen(FolderDetailUiState(
                    folder = FolderDetail("folder-1", "Camera", listOf(PhotoItem("1", "photo", PhotoSyncStatus.Pending))),
                    batch = batch.value), {}, {}, {}, {}, {})
            }
        }
        composeRule.onNodeWithTag("batch_progress").assertTextEquals(context.getString(R.string.batch_preparing, 37, 500))
        composeRule.runOnIdle { batch.value = batch.value.copy(phase = MediaBatchPhase.Pending, waitingForWifi = true) }
        composeRule.onNodeWithTag("batch_progress").assertTextEquals(context.getString(R.string.batch_waiting_wifi, 1))
        composeRule.runOnIdle { batch.value = batch.value.copy(phase = MediaBatchPhase.Synchronizing, waitingForWifi = false, completed = 42) }
        composeRule.onNodeWithTag("batch_progress").assertTextEquals(context.getString(R.string.batch_syncing, 42, 500))
        composeRule.runOnIdle { batch.value = batch.value.copy(phase = MediaBatchPhase.Failed, failed = 1) }
        composeRule.onNodeWithTag("batch_progress").assertTextEquals(context.getString(R.string.batch_failed, 1))
        composeRule.runOnIdle { batch.value = batch.value.copy(phase = MediaBatchPhase.Completed, completed = 500) }
        composeRule.onNodeWithTag("batch_progress").assertTextEquals(context.getString(R.string.batch_completed, 500))
    }

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
                                PhotoItem("5", "IMG_15", PhotoSyncStatus.RemoteOnly, serverFileId = 15),
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
        composeRule.onAllNodesWithTag("photo_cell").assertCountEquals(5)
        composeRule.onAllNodesWithTag("photo_status", useUnmergedTree = true).assertCountEquals(5)
        composeRule.onAllNodesWithTag("cloud_badge", useUnmergedTree = true).assertCountEquals(1)
    }
}

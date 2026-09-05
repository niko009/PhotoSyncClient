package com.photosync.android.data

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.photosync.android.ui.PhotoSyncApp
import com.photosync.android.ui.theme.PhotoSyncTheme
import com.photosync.android.domain.model.ConnectionStatus
import com.photosync.android.domain.model.PhotoSyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import java.io.File
import java.util.UUID
import java.util.Locale

/** Opt-in real HTTP test. Pass -e photosyncTestServer http://10.0.2.2:5188. */
class DeviceNetworkSmokeTest {
    @get:Rule val composeRule = createComposeRule()
    @Test fun uploadDownloadAndDeviceIsolation() = runBlocking {
        val origin = InstrumentationRegistry.getArguments().getString("photosyncTestServer")
        assumeTrue("An isolated test server must be supplied", origin != null)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val firstContext = IsolatedContext(base)
        val secondContext = IsolatedContext(base)
        val firstApi = PhotoSyncApiClient(origin!!, DeviceIdentity(firstContext))
        val secondApi = PhotoSyncApiClient(origin, DeviceIdentity(secondContext))
        val preferences = PreferencesStore(firstContext)
        preferences.updateServerUrl(origin)
        val repository = NetworkPhotoSyncRepository(firstContext, firstApi, preferences)
        repository.refresh()
        val connection = withTimeout(15_000) {
            repository.observeStats().first { it.connectionStatus == ConnectionStatus.Online || it.connectionStatus == ConnectionStatus.Offline }
        }
        assertEquals(ConnectionStatus.Online, connection.connectionStatus)
        repository.addFolder("Семейный альбом")
        val folder = repository.observeFolders().first().single()
        val fixture = File(firstContext.filesDir, "test-photo.png")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(168, 72, 50))
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        repository.uploadToFolder(folder.id, Uri.fromFile(fixture))
        val photo = repository.observeFolder(folder.id).first()!!.photos.single()
        assertEquals(PhotoSyncStatus.Synced, photo.status)
        assertArrayEquals(fixture.readBytes(), firstApi.downloadFile(photo.serverFileId!!))
        repository.downloadPhoto(folder.id, photo.id)
        val downloaded = repository.observeFolder(folder.id).first()!!.photos.single()
        assertArrayEquals(fixture.readBytes(), File(Uri.parse(downloaded.localUri!!).path!!).readBytes())

        val firstId = firstApi.registerDevice(firstApi.deviceUuid(), "Smoke A", "test").deviceId
        secondApi.registerDevice(secondApi.deviceUuid(), "Smoke B", "test")
        assertThrows(IllegalStateException::class.java) { secondApi.getFiles(firstId) }
        assertEquals(0, secondApi.getSummary().fileCount)
        assertThrows(IllegalStateException::class.java) { secondApi.downloadFile(photo.serverFileId!!) }
        assertEquals(firstApi.deviceUuid(), PhotoSyncApiClient(origin, DeviceIdentity(firstContext)).deviceUuid())

        val config = Configuration(base.resources.configuration).apply { setLocale(Locale("ru")) }
        val localized = base.createConfigurationContext(config)
        composeRule.setContent {
            val registryOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
            val backOwner = requireNotNull(LocalOnBackPressedDispatcherOwner.current)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalActivityResultRegistryOwner provides registryOwner, LocalOnBackPressedDispatcherOwner provides backOwner) {
                PhotoSyncTheme(false) { PhotoSyncApp(repository, FamilyApiClient(preferences, DeviceIdentity(firstContext))) }
            }
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("folder_card").fetchSemanticsNodes().size == 1 }
        capture(base, "01-albums")
        composeRule.onNodeWithText("Сохранение").performClick()
        capture(base, "02-sync")
        composeRule.onNodeWithText("Альбомы").performClick()
        composeRule.onNodeWithTag("folder_card").performClick()
        composeRule.onNodeWithTag("photo_cell").assertExists()
        capture(base, "03-folder")
        composeRule.onNodeWithTag("photo_cell").performClick()
        capture(base, "04-viewer")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_settings").performClick()
        capture(base, "05-settings")
    }

    private fun capture(context: Context, name: String) {
        composeRule.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        requireNotNull(screenshot)
        File(context.getExternalFilesDir("screenshots"), name + ".png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "smoke_" + UUID.randomUUID()
        private val root = File(base.cacheDir, prefix).apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    }
}

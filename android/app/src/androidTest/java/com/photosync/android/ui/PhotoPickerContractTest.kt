package com.photosync.android.ui

import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhotoPickerContractTest {
    @Test
    fun addPhotosUsesGalleryPickerInsteadOfDocumentBrowser() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val contract = ActivityResultContracts.PickMultipleVisualMedia(50)
        val intent = contract.createIntent(
            context,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
        )

        assertNotEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(MediaStore.ACTION_PICK_IMAGES, intent.action)
        }
    }
}

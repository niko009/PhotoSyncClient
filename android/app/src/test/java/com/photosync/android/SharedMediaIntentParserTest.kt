package com.photosync.android

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedMediaIntentParserTest {
    @Test
    fun parsesAndDeduplicatesMultipleSharedItems() {
        val first = Uri.parse("content://gallery/one")
        val second = Uri.parse("content://gallery/two")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
            clipData = ClipData.newRawUri("photo", first)
        }

        assertEquals(listOf(first, second), SharedMediaIntentParser.parse(intent))
    }

    @Test
    fun rejectsUnsupportedShareTypes() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://gallery/one"))
        }

        assertTrue(SharedMediaIntentParser.parse(intent).isEmpty())
    }
}

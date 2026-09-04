package com.photosync.android.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test fun newerManifestIsOffered() = assertTrue(isNewerVersion(10, 9))
    @Test fun sameOrOlderManifestIsIgnored() {
        assertFalse(isNewerVersion(9, 9))
        assertFalse(isNewerVersion(8, 9))
    }
}

package com.photosync.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadFailureClassificationTest {
    @Test fun interruptedUploadAndServerFailuresAreRetryable() {
        assertTrue(PhotoSyncApiException(400, "UPLOAD_INTERRUPTED", message = "broken chunk").isTransient)
        assertTrue(PhotoSyncApiException(408, null, message = "timeout").isTransient)
        assertTrue(PhotoSyncApiException(429, null, message = "rate limit").isTransient)
        assertTrue(PhotoSyncApiException(502, null, message = "proxy error").isTransient)
    }

    @Test fun sizeAuthAndValidationFailuresAreTerminal() {
        assertFalse(PhotoSyncApiException(413, "FILE_TOO_LARGE", message = "too large").isTransient)
        assertFalse(PhotoSyncApiException(401, null, message = "unauthorized").isTransient)
        assertFalse(PhotoSyncApiException(400, "INVALID_UPLOAD_METADATA", message = "invalid").isTransient)
    }
}

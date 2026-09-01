package com.photosync.android.data

import org.junit.Assert.*
import org.junit.Test

class DeviceCredentialsTest {
    @Test fun stableForSameInstallationAndCanonicalOrigin() {
        val master = ByteArray(32) { it.toByte() }
        val credentials = deriveDeviceCredentials(master, "https://example.com")
        assertEquals(credentials, deriveDeviceCredentials(master, "https://EXAMPLE.com:443/"))
        assertEquals(64, credentials.second.length)
        assertTrue(credentials.second.matches(Regex("[0-9a-f]{64}")))
        java.util.UUID.fromString(credentials.first)
    }

    @Test fun resetOrOtherServerCreatesSeparateIdentity() {
        val master = ByteArray(32) { it.toByte() }
        val original = deriveDeviceCredentials(master, "https://example.com")
        val otherServer = deriveDeviceCredentials(master, "https://other.example.com")
        val afterReset = deriveDeviceCredentials(ByteArray(32) { (it + 1).toByte() }, "https://example.com")
        assertNotEquals(original.first, otherServer.first)
        assertNotEquals(original.second, otherServer.second)
        assertNotEquals(original.first, afterReset.first)
        assertNotEquals(original.second, afterReset.second)
    }
}

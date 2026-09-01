package com.photosync.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    @Test fun canonicalOrigin() {
        assertEquals("https://photos.example.com", ServerAddress.normalize(" HTTPS://Photos.Example.Com:443/ "))
        assertEquals("http://192.168.1.2:5187", ServerAddress.normalize("http://192.168.1.2:5187/"))
        assertEquals("http://10.0.2.2:5187", ServerAddress.normalize("http://10.0.2.2:5187"))
    }

    @Test fun rejectsUnsafeOrAmbiguousOrigins() {
        listOf("http://photos.example.com", "http://8.8.8.8", "https://a.com/photos",
            "https://secret@a.com", "https://a.com?token=secret", "https://a.com#fragment",
            "ftp://a.com", "https://a.com:65536", "https://a.com:0", "http://172.32.1.1").forEach {
            assertThrows(it, Exception::class.java) { ServerAddress.normalize(it) }
        }
    }
}

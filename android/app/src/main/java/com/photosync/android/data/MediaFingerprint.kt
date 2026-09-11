package com.photosync.android.data

import java.io.InputStream
import java.security.MessageDigest

internal data class MediaFingerprint(val sizeBytes: Long, val sha256: String)

internal fun fingerprintMedia(input: InputStream): MediaFingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var size = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        size += read
        digest.update(buffer, 0, read)
    }
    return MediaFingerprint(size, digest.digest().joinToString("") { "%02x".format(it) })
}

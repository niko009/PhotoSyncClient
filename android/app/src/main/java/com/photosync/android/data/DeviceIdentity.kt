package com.photosync.android.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Installation credential, deliberately excluded from backup and device transfer. */
class DeviceIdentity(context: Context) {
    private val master: ByteArray = synchronized(lock) {
        val file = AtomicFile(File(context.noBackupFilesDir, "device-identity-v2"))
        if (file.baseFile.exists()) {
            file.openRead().use { it.readBytes() }.also { require(it.size == 32) }
        } else {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val stream = file.startWrite()
            try { stream.write(key); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
            key
        }
    }

    fun credentials(baseUrl: String): Pair<String, String> = deriveDeviceCredentials(master, baseUrl)

    companion object { private val lock = Any() }
}

internal fun deriveDeviceCredentials(master: ByteArray, baseUrl: String): Pair<String, String> {
        require(master.size == 32)
        val origin = ServerAddress.normalize(baseUrl)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        val secret = mac.doFinal("photosync-device-v2:$origin".toByteArray())
        val uuid = UUID.nameUUIDFromBytes(mac.doFinal("photosync-id-v2:$origin".toByteArray())).toString()
        return uuid to secret.joinToString("") { "%02x".format(it) }
}

object ServerAddress {
    fun normalize(value: String): String {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: error("Server host is required")
        require(scheme == "https" || scheme == "http") { "Use http or https" }
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Use a server origin without credentials or query" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Use a server origin without a path" }
        val octets = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        val local = host == "localhost" || host == "[::1]" || host.endsWith(".local") ||
            (host.split('.').size == 4 && octets.size == 4 && (octets[0] == 10 || octets[0] == 127 ||
                (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && octets[1] in 16..31)))
        require(scheme == "https" || local) { "Public servers require HTTPS" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid port" }
        val port = uri.port.takeUnless { it == -1 || it == (if (scheme == "https") 443 else 80) }
        return "$scheme://$host${port?.let { ":$it" }.orEmpty()}"
    }
}

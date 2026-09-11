package com.photosync.android.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MediaStreamingTest {
    @Test
    fun largeMediaUsesBoundedReadsAndRoundTripsThroughHttp() {
        val length = 32L * 1024 * 1024
        val expected = ZeroStream(length).use(::fingerprintMedia)
        assertEquals(length, expected.sizeBytes)
        val received = AtomicLong()
        val transferEncoding = AtomicReference<String>()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 30_000
        val serverError = AtomicReference<Throwable>()
        val responder = thread(isDaemon = true) {
            try {
                repeat(2) { index -> server.accept().use { socket ->
                    socket.soTimeout = 30_000
                    val input = socket.getInputStream().buffered()
                    val output = socket.getOutputStream().buffered()
                    input.line() // request line
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.line()
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    if (index == 0) {
                        transferEncoding.set(headers["transfer-encoding"])
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            var chunk = input.line().substringBefore(';').toInt(16)
                            if (chunk == 0) { input.line(); break }
                            while (chunk > 0) {
                                val read = input.read(buffer, 0, minOf(chunk, buffer.size))
                                check(read > 0)
                                received.addAndGet(read.toLong())
                                chunk -= read
                            }
                            input.line()
                        }
                        val response = """{"server_file_id":1,"stored_name":"video.mp4","relative_path":"video.mp4"}""".toByteArray()
                        output.write("HTTP/1.1 201 Created\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(response)
                    } else {
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
                        ZeroStream(length).use { it.copyTo(output, 64 * 1024) }
                    }
                    output.flush()
                } }
            } catch (error: Throwable) { serverError.set(error) }
        }
        val context = RuntimeEnvironment.getApplication()
        val target = File(context.cacheDir, "stream-test.mp4")
        try {
            val api = PhotoSyncApiClient("http://127.0.0.1:${server.localPort}", DeviceIdentity(context))
            val result = api.uploadFileToAlbum(1, "video.mp4", "video/mp4", length, expected.sha256,
                "2026-09-07T00:00:00Z", { ZeroStream(length) })
            assertEquals(1, result.serverFileId)
            assertEquals("chunked", transferEncoding.get())
            assertTrue(received.get() > length && received.get() < length + 4096)
            api.downloadFile(1, target)
            assertEquals(expected, target.inputStream().use(::fingerprintMedia))
            responder.join(5_000)
            assertNull(serverError.get())
        } finally {
            server.close()
            target.delete()
        }
    }

    private fun InputStream.line(): String = buildString {
        while (true) {
            val next = read()
            check(next >= 0) { "Unexpected end of HTTP headers" }
            if (next == 10) break
            if (next != 13) append(next.toChar())
        }
    }

    private class ZeroStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            check(length <= 64 * 1024) { "Unbounded media read" }
            if (remaining <= 0) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            bytes.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }
}

package com.photosync.android.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ResumableUploadProtocolTest {
    @Test fun photosAndLargeVideosUseBoundedChunks() {
        for (size in listOf(26L, 101L, 301L).map { it * 1024 * 1024 }) {
            TestUploadServer(size).use { server ->
                assertEquals(1, upload(server, size))
                assertEquals(size, server.received)
                assertTrue(server.largestBody <= PhotoSyncApiClient.RESUMABLE_CHUNK_BYTES)
                assertEquals(1, server.completions)
                assertEquals(1, server.createdFiles)
                assertTrue(server.requests.all { it.startsWith("POST /api/files/uploads") ||
                    it.startsWith("PUT /api/files/uploads/") })
            }
        }
    }

    @Test fun interruptedChunkRestartsAtDurableOffsetWithoutDuplicate() {
        val size = 10L * 1024 * 1024
        TestUploadServer(size, interruptSecondChunk = true).use { server ->
            assertEquals(1, upload(server, size))
            assertEquals(size, server.received)
            assertEquals(2, server.starts)
            assertEquals(1, server.completions)
            assertEquals(1, server.createdFiles)
            assertEquals(1, server.requests.count { it.startsWith("PUT /api/files/uploads/id?offset=0") })
        }
    }

    @Test fun lostCompletionResponseDoesNotCreateAnotherFile() {
        val size = 1024L
        TestUploadServer(size, loseCompletionResponse = true).use { server ->
            assertEquals(1, upload(server, size))
            assertTrue(server.starts + server.completions >= 3)
            assertEquals(1, server.createdFiles)
        }
    }

    @Test fun maximumConfiguredFileStillUsesFourMebibyteBodies() {
        val maximum = 2L * 1024 * 1024 * 1024
        assertEquals(4 * 1024 * 1024, resumableChunkSize(maximum, 0))
        assertEquals(4 * 1024 * 1024, resumableChunkSize(maximum, maximum - 4L * 1024 * 1024))
        assertEquals(1, resumableChunkSize(maximum, maximum - 1))
    }

    private fun upload(server: TestUploadServer, size: Long): Int {
        val identity = DeviceIdentity(RuntimeEnvironment.getApplication())
        val api = PhotoSyncApiClient("http://127.0.0.1:${server.port}", identity)
        return api.uploadFileToAlbum(1, "media.mp4", "video/mp4", size, "0".repeat(64),
            "2026-09-25T00:00:00Z", { ZeroStream(size) }).serverFileId
    }

    private class TestUploadServer(
        private val size: Long,
        private val interruptSecondChunk: Boolean = false,
        private val loseCompletionResponse: Boolean = false,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val requests = mutableListOf<String>()
        var received = 0L
        var largestBody = 0L
        var starts = 0
        var completions = 0
        var createdFiles = 0
        private var interrupted = false
        private var completionResponseLost = false
        private val serverError = AtomicReference<Throwable>()
        private val responder: Thread

        init {
            server.soTimeout = 30_000
            responder = thread(isDaemon = true) {
                try {
                    while (!server.isClosed) server.accept().use { socket ->
                        socket.soTimeout = 30_000
                        val input = socket.getInputStream().buffered()
                        val output = socket.getOutputStream().buffered()
                        val request = input.line()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.line()
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        handle(request, headers["content-length"]?.toLongOrNull() ?: 0, input, output)
                    }
                } catch (error: SocketException) {
                    if (!server.isClosed) serverError.set(error)
                } catch (error: Throwable) { serverError.set(error) }
            }
        }

        private fun handle(request: String, length: Long, input: InputStream, output: OutputStream) {
            val method = request.substringBefore(' ')
            val path = request.substringAfter(' ').substringBefore(' ')
            requests += "$method $path"
            when {
                path == "/api/files/uploads" && method == "POST" -> {
                    drain(input, length)
                    starts++
                    val completed = if (createdFiles > 0) ",\"completed_file\":$file" else ""
                    respond(output, "{\"upload_id\":\"id\",\"received_bytes\":$received,\"size_bytes\":$size$completed}")
                }
                path.startsWith("/api/files/uploads/id?offset=") && method == "PUT" -> {
                    val offset = path.substringAfter("offset=").toLong()
                    val bodySize = drain(input, length)
                    largestBody = maxOf(largestBody, bodySize)
                    if (interruptSecondChunk && !interrupted && offset == 4L * 1024 * 1024) {
                        interrupted = true
                        return
                    }
                    check(offset == received)
                    received += bodySize
                    respond(output, "{\"upload_id\":\"id\",\"received_bytes\":$received,\"size_bytes\":$size}")
                }
                path == "/api/files/uploads/id/complete" && method == "POST" -> {
                    drain(input, length)
                    check(received == size)
                    completions++
                    if (createdFiles == 0) createdFiles++
                    if (loseCompletionResponse && !completionResponseLost) {
                        completionResponseLost = true
                        return
                    }
                    respond(output, file)
                }
                else -> error("Unexpected request: $request")
            }
        }

        private fun drain(input: InputStream, length: Long): Long {
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            while (count < length) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - count).toInt())
                check(read > 0)
                count += read
            }
            return count
        }

        private fun respond(output: OutputStream, json: String) {
            val bytes = json.toByteArray()
            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(bytes)
            output.flush()
        }

        override fun close() {
            server.close()
            responder.join(5_000)
            assertNull(serverError.get())
        }

        private fun InputStream.line(): String = buildString {
            while (true) {
                val next = read()
                check(next >= 0) { "Unexpected end of HTTP headers" }
                if (next == 10) break
                if (next != 13) append(next.toChar())
            }
        }

        private companion object {
            const val file = "{\"server_file_id\":1,\"stored_name\":\"media.mp4\",\"relative_path\":\"media.mp4\"}"
        }
    }

    private class ZeroStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            bytes.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }
}

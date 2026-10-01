package com.photosync.android.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.work.Configuration
import androidx.work.WorkManager
import com.photosync.android.domain.model.PhotoSyncStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SyncStormRegressionTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("photosync_registration_v1", 0).edit().clear().commit()
        context.getSharedPreferences("photosync_offline_queue_v1", 0).edit().clear().commit()
        try { WorkManager.initialize(context, Configuration.Builder().build()) } catch (_: IllegalStateException) { }
    }

    @Test fun tenUploadsDoNotRegisterOrRefreshPerFileAndSurviveRestartAndRefresh429() = runBlocking {
        CounterServer().use { server ->
            val network = repository(server)
            network.addFolder("Camera")
            val folder = network.observeFolders().first().first().id
            val beforeRefresh = server.count("/api/stats/summary")
            val queue = OfflineFirstPhotoSyncRepository(context, network, networkAvailable = { true })
            val media = (1..12).map { i -> Uri.fromFile(File(context.cacheDir, "storm-$i.jpg").apply { writeText("photo-$i") }) }
            assertTrue(queue.enqueueSharedMedia(folder, media))
            assertEquals(12, server.count("/api/files/uploads/id/complete"))
            assertEquals(1, server.count("/api/devices/register"))
            assertEquals(beforeRefresh, server.count("/api/stats/summary"))
            assertEquals("[]", queueJson())
            assertEquals(12, network.observeFolder(folder).first()!!.photos.count { it.status == PhotoSyncStatus.Synced })
            server.refresh429 = true
            queue.refresh()
            queue.refresh()
            assertEquals(beforeRefresh + 1, server.count("/api/stats/summary"))
            val restarted = OfflineFirstPhotoSyncRepository(context, repository(server), networkAvailable = { true })
            restarted.syncQueuedUploadsOnce()
            assertEquals(12, server.count("/api/files/uploads/id/complete"))
            assertEquals("[]", queueJson())
            assertEquals(12, restarted.observeFolder(folder).first()!!.photos.count { it.status == PhotoSyncStatus.Synced })
        }
    }

    @Test fun concurrentAndRestartedRegistrationIsSingleAndMetadataChangesRegisterOnce() = runBlocking {
        CounterServer().use { server ->
            val identity = DeviceIdentity(context)
            val clients = (1..12).map { PhotoSyncApiClient(server.url, identity) }
            clients.map { client -> async(Dispatchers.IO) { client.registerDevice(client.deviceUuid(), "phone", "1") } }.forEach { it.await() }
            PhotoSyncApiClient(server.url, DeviceIdentity(context)).let { it.registerDevice(it.deviceUuid(), "phone", "1") }
            assertEquals(1, server.count("/api/devices/register"))
            clients.first().let { it.registerDevice(it.deviceUuid(), "phone", "2") }
            assertEquals(2, server.count("/api/devices/register"))
        }
    }

    @Test fun registration429PersistsRetryAfterAcrossClients() {
        CounterServer().use { server ->
            server.registration429 = true
            repeat(12) {
                val client = PhotoSyncApiClient(server.url, DeviceIdentity(context))
                val error = runCatching { client.registerDevice(client.deviceUuid(), "phone", "1") }.exceptionOrNull()
                assertTrue(error is PhotoSyncApiException)
                assertTrue((error as PhotoSyncApiException).retryAfterMillis!! > 110_000)
            }
            assertEquals(1, server.count("/api/devices/register"))
        }
    }

    @Test fun legacyFailedQueueRecord429DoesNotRetryInTightLoopOrAfterRestart() = runBlocking {
        val base = FakePhotoSyncRepository()
        var attempts = 0
        val delegate = object : com.photosync.android.domain.repository.PhotoSyncRepository by base {
            override suspend fun uploadStagedMedia(folderId: String, uploadUri: Uri, sourceUri: Uri,
                displayName: String, mimeType: String, cleanupPolicy: com.photosync.android.domain.model.PhotoCleanupPolicy?): Boolean {
                attempts++
                throw PhotoSyncApiException(429, null, 120_000, "limited")
            }
        }
        context.getSharedPreferences("photosync_offline_queue_v1", 0).edit().putString("items",
            """[{"id":"legacy","folder_id":"folder-1","title":"old.jpg","mime_type":"image/jpeg","local_uri":"file:///old.jpg","error_code":"TEMPORARY_NETWORK_FAILURE"}]""").commit()
        val queue = OfflineFirstPhotoSyncRepository(context, delegate, networkAvailable = { true })
        repeat(12) { queue.syncQueuedUploadsOnce() }
        OfflineFirstPhotoSyncRepository(context, delegate, networkAvailable = { true }).syncQueuedUploadsOnce()
        assertEquals(1, attempts)
        assertTrue(queueJson().contains("next_attempt_at"))
        assertFalse(queueJson().contains("\"terminal_failure\":true"))
    }

    @Test fun committedLegacyQueueRecordIsRemovedWithoutUploadEvenWhenOriginalWasDeleted() = runBlocking {
        val photo = com.photosync.android.domain.model.PhotoItem("committed", "old.jpg", PhotoSyncStatus.Synced,
            serverFileId = 7, uploadSourceUri = "file:///deleted.jpg")
        val base = FakePhotoSyncRepository(seedFolders = listOf(FolderRecord("folder-1", "Camera", listOf(photo))))
        var uploads = 0
        val delegate = object : com.photosync.android.domain.repository.PhotoSyncRepository by base {
            override suspend fun uploadStagedMedia(folderId: String, uploadUri: Uri, sourceUri: Uri,
                displayName: String, mimeType: String, cleanupPolicy: com.photosync.android.domain.model.PhotoCleanupPolicy?): Boolean {
                uploads++; return true
            }
        }
        context.getSharedPreferences("photosync_offline_queue_v1", 0).edit().putString("items",
            """[{"id":"old","folder_id":"folder-1","title":"old.jpg","mime_type":"image/jpeg","local_uri":"file:///deleted.jpg"}]""").commit()
        OfflineFirstPhotoSyncRepository(context, delegate, networkAvailable = { true }).syncQueuedUploadsOnce()
        assertEquals(0, uploads)
        assertEquals("[]", queueJson())
    }

    @Test fun newServerOriginNeedsItsOwnRegistration() {
        CounterServer().use { first -> CounterServer().use { second ->
            val client = PhotoSyncApiClient(first.url, DeviceIdentity(context))
            client.registerDevice(client.deviceUuid(), "phone", "1")
            val firstUuid = client.deviceUuid()
            client.updateBaseUrl(second.url)
            assertNotEquals(firstUuid, client.deviceUuid())
            client.registerDevice(client.deviceUuid(), "phone", "1")
            assertEquals(1, first.count("/api/devices/register"))
            assertEquals(1, second.count("/api/devices/register"))
        } }
    }

    @Test fun realUpload429StopsBatchAndRestartUntilRetryAfter() = runBlocking {
        CounterServer().use { server ->
            val network = repository(server)
            network.addFolder("Camera")
            val folder = network.observeFolders().first().first().id
            server.upload429 = true
            val queue = OfflineFirstPhotoSyncRepository(context, network, networkAvailable = { true })
            val media = (1..12).map { Uri.fromFile(File(context.cacheDir, "limited-$it.jpg").apply { writeText("image-$it") }) }
            queue.enqueueSharedMedia(folder, media)
            repeat(12) { queue.syncQueuedUploadsOnce() }
            OfflineFirstPhotoSyncRepository(context, repository(server), networkAvailable = { true }).syncQueuedUploadsOnce()
            assertEquals(1, server.count("/api/files/uploads"))
            assertEquals(0, server.count("/api/files/uploads/id/complete"))
            assertEquals(1, server.count("/api/devices/register"))
        }
    }

    private fun queueJson() = context.getSharedPreferences("photosync_offline_queue_v1", 0).getString("items", "").orEmpty()
    private fun repository(server: CounterServer) = NetworkPhotoSyncRepository(context,
        PhotoSyncApiClient(server.url, DeviceIdentity(context)), PreferencesStore(context))

    private class CounterServer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        private val counters = ConcurrentHashMap<String, AtomicInteger>()
        var registration429 = false
        var refresh429 = false
        var upload429 = false
        private var fileId = 0
        private var received = 0L
        fun count(path: String) = counters[path]?.get() ?: 0
        private val responder = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                socket.use {
                val input = socket.getInputStream().buffered()
                fun line(): String = buildString {
                    while (true) { val next = input.read(); check(next >= 0); if (next == 10) break; if (next != 13) append(next.toChar()) }
                }
                val request = line().split(' ')
                val method = request[0]
                val path = request[1].substringBefore('?')
                var length = 0
                while (true) { val header = line(); if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                }
                counters.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) { val count = input.read(body, offset, length - offset); check(count > 0); offset += count }
                val limited = registration429 && path == "/api/devices/register" || refresh429 && path == "/api/stats/summary" || upload429 && path == "/api/files/uploads"
                val response = if (limited) "{}" else when (path) {
                    "/api/server/capabilities" -> """{"device_auth":true}"""
                    "/api/devices/register" -> """{"device_id":1,"registered":true}"""
                    "/api/auth/google/me" -> "{}"
                    "/api/stats/summary" -> """{"device_count":1,"file_count":0,"photo_count":0,"video_count":0,"bytes_total":0}"""
                    "/api/devices" -> """{"devices":[]}"""
                    "/api/albums/accessible" -> """{"albums":[]}"""
                    "/api/albums" -> if (method == "POST") """{"album_id":1,"server_folder_path":"Camera","created":true}"""
                        else """{"albums":[{"album_id":1,"name":"Camera","server_folder_path":"Camera"}]}"""
                    "/api/files/album/1" -> """{"files":[]}"""
                    "/api/files/uploads" -> { received = 0; """{"upload_id":"id","received_bytes":0}""" }
                    "/api/files/uploads/id" -> { received += body.size; """{"received_bytes":$received}""" }
                    "/api/files/uploads/id/complete" -> { fileId++; """{"server_file_id":$fileId,"stored_name":"photo.jpg","relative_path":"Camera/$fileId.jpg"}""" }
                    else -> error("Unexpected request $path")
                }
                val output = socket.getOutputStream()
                val retryHeader = if (limited) "Retry-After: 120\r\n" else ""
                val status = if (limited) "429 Too Many Requests" else "200 OK"
                output.write("HTTP/1.1 $status\r\n${retryHeader}Content-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(response.toByteArray())
                output.flush()
                }
            }
        }
        override fun close() { server.close(); responder.join(5000) }
    }
}

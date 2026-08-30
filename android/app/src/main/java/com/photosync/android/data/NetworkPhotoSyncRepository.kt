package com.photosync.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.photosync.android.data.remote.FileItemDto
import com.photosync.android.domain.model.DashboardStats
import com.photosync.android.domain.model.FolderDetail
import com.photosync.android.domain.model.FolderSummary
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.model.PhotoItem
import com.photosync.android.domain.model.PhotoSyncStatus
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class NetworkPhotoSyncRepository(
    context: Context,
    private val apiClient: PhotoSyncApiClient,
    private val preferencesStore: PreferencesStore,
) : PhotoSyncRepository {

    private val appContext = context.applicationContext
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stats = MutableStateFlow(DashboardStats())
    private val folders = MutableStateFlow<List<FolderSummary>>(emptyList())
    private val folderDetails = MutableStateFlow<Map<String, FolderDetail>>(emptyMap())
    private val localFolders = MutableStateFlow(loadLocalFolders())
    private val localPhotos = MutableStateFlow(loadLocalPhotos())
    private val folderPolicies = MutableStateFlow(loadFolderPolicies())
    private val serverUrl = MutableStateFlow(apiClient.currentBaseUrl())
    private val deviceUuid = getOrCreateDeviceUuid()
    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    init {
        restoreLocalState()
        repositoryScope.launch {
            runCatching { refresh() }
                .onFailure { error -> Log.e(TAG, "Initial refresh failed", error) }
        }
    }

    override fun observeServerUrl(): Flow<String> = serverUrl.asStateFlow()

    override fun observeGlobalPhotoCleanupPolicy(): Flow<PhotoCleanupPolicy> =
        preferencesStore.observeGlobalPhotoCleanupPolicy()

    override fun observeFolderPhotoCleanupPolicy(folderId: String): Flow<PhotoCleanupPolicy?> =
        folderPolicies.asStateFlow().map { policies -> policies[folderId] }

    override fun observeStats(): Flow<DashboardStats> = stats.asStateFlow()

    override fun observeFolders(): Flow<List<FolderSummary>> = folders.asStateFlow()

    override fun observeFolder(folderId: String): Flow<FolderDetail?> = folderDetails.asStateFlow().map { details ->
        details[folderId]
    }

    override suspend fun addFolder(name: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val normalized = name.trim()
                if (normalized.isBlank()) return@withContext

                val localId = UUID.randomUUID().toString()
                val localSummary = FolderSummary(
                    id = localId,
                    name = normalized,
                    photoCount = 0,
                    syncedCount = 0,
                    pendingCount = 0,
                    failedCount = 0,
                    statusLabel = "Pending sync",
                )
                val localDetail = FolderDetail(
                    id = localId,
                    name = normalized,
                    photos = loadLocalPhotosForFolder(localId),
                )
                upsertLocalFolder(localSummary, localDetail)

                runCatching {
                    apiClient.registerDevice(
                        deviceUuid = deviceUuid,
                        deviceName = deviceName,
                        appVersion = APP_VERSION,
                    )
                    apiClient.createAlbum(deviceUuid = deviceUuid, albumName = normalized)
                }.onFailure { error ->
                    Log.e(TAG, "Server folder creation failed for $normalized", error)
                }

                refresh()
            }
        }
            .onFailure { error -> Log.e(TAG, "Add folder failed: $name", error) }
    }

    override suspend fun refresh() {
        runCatching {
            withContext(Dispatchers.IO) {
                val registration = apiClient.registerDevice(
                    deviceUuid = deviceUuid,
                    deviceName = deviceName,
                    appVersion = APP_VERSION,
                )
                val summary = apiClient.getSummary()
                val serverAlbums = apiClient.getAlbums(deviceUuid)
                val serverFiles = apiClient.getFiles(registration.deviceId)
                    .groupBy { it.albumName }
                val previousDetails = folderDetails.value
                val details = linkedMapOf<String, FolderDetail>()
                val summaries = linkedMapOf<String, FolderSummary>()

                localFolders.value.values.forEach { localFolder ->
                    val previousPhotos = previousDetails[localFolder.id]?.photos.orEmpty()
                    val localCachedPhotos = localPhotos.value[localFolder.id].orEmpty()
                    val mergedPhotos = mergePhotos(
                        previousPhotos,
                        localCachedPhotos,
                        serverFiles[localFolder.name].orEmpty(),
                        localFolder.id,
                    )
                    val detail = localFolder.copy(photos = mergedPhotos)
                    details[detail.id] = detail
                    summaries[detail.id] = detail.toSummary()
                }

                serverAlbums.forEach { album ->
                    val matchingLocal = localFolders.value.values.firstOrNull { it.name == album.name }
                    val folderId = matchingLocal?.id ?: album.id.toString()
                    val previousPhotos = previousDetails[folderId]?.photos.orEmpty()
                    val localCachedPhotos = localPhotos.value[folderId].orEmpty()
                    val mergedPhotos = mergePhotos(
                        previousPhotos,
                        localCachedPhotos,
                        serverFiles[album.name].orEmpty(),
                        folderId,
                    )
                    val detail = FolderDetail(
                        id = folderId,
                        name = album.name,
                        photos = mergedPhotos,
                    )
                    val summary = detail.toSummary()
                    details[folderId] = detail.copy(id = folderId)
                    summaries[folderId] = summary
                }

                folderDetails.value = details
                folders.value = summaries.values.toList()
                stats.value = DashboardStats(
                    totalFolders = folders.value.size,
                    totalPhotos = maxOf(summary.fileCount, folders.value.sumOf { it.photoCount }),
                    syncedPhotos = folders.value.sumOf { it.syncedCount },
                    pendingPhotos = folders.value.sumOf { it.pendingCount },
                    failedPhotos = folders.value.sumOf { it.failedCount },
                )
            }
        }
            .onFailure { error ->
                Log.e(TAG, "Refresh failed", error)
                restoreLocalState()
            }
    }

    override suspend fun updateServerUrl(serverUrl: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                preferencesStore.updateServerUrl(serverUrl)
                val normalized = preferencesStore.getServerUrl()
                apiClient.updateBaseUrl(normalized)
                this@NetworkPhotoSyncRepository.serverUrl.value = apiClient.currentBaseUrl()
                refresh()
            }
        }
            .onFailure { error -> Log.e(TAG, "Update server URL failed", error) }
    }

    override suspend fun updateGlobalPhotoCleanupPolicy(policy: PhotoCleanupPolicy) {
        withContext(Dispatchers.IO) {
            preferencesStore.updateGlobalPhotoCleanupPolicy(policy)
        }
    }

    override suspend fun updateFolderPhotoCleanupPolicy(folderId: String, policy: PhotoCleanupPolicy?) {
        withContext(Dispatchers.IO) {
            folderPolicies.value = folderPolicies.value.toMutableMap().apply {
                if (policy == null) {
                    remove(folderId)
                } else {
                    put(folderId, policy)
                }
            }
            persistFolderPolicies()
        }
    }

    override suspend fun downloadPhoto(folderId: String, photoId: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val photo = localPhotos.value[folderId].orEmpty().firstOrNull { it.id == photoId }
                    ?: folderDetails.value[folderId]?.photos.orEmpty().firstOrNull { it.id == photoId }
                    ?: return@withContext
                val serverFileId = photo.serverFileId ?: return@withContext
                val bytes = apiClient.downloadFile(serverFileId)
                val downloadsDir = File(appContext.filesDir, "downloads/$folderId")
                downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, StoragePathResolverSafeName.make(photo.title))
                targetFile.writeBytes(bytes)
                val localUri = Uri.fromFile(targetFile).toString()
                val thumbnailPath = ensureThumbnailFromFile(folderId, photo.id, targetFile) ?: photo.thumbnailPath
                updateLocalPhoto(
                    folderId,
                    photo.copy(
                        status = PhotoSyncStatus.Synced,
                        localUri = localUri,
                        thumbnailPath = thumbnailPath,
                        mimeType = photo.mimeType ?: "image/jpeg",
                    ),
                )
                restoreLocalState()
            }
        }.onFailure { error ->
            Log.e(TAG, "Download photo failed for folderId=$folderId photoId=$photoId", error)
        }
    }

    override suspend fun uploadToFolder(folderId: String, uri: Uri) {
        runCatching {
            withContext(Dispatchers.IO) {
                val folder = folderDetails.value[folderId] ?: return@withContext
                val fileBytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext
                val mimeType = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
                val originalName = resolveDisplayName(uri) ?: "upload-${System.currentTimeMillis()}"
                val sha256 = fileBytes.sha256()
                val tempPhotoId = "$folderId-${System.currentTimeMillis()}"

                val thumbnailPath = ensureThumbnail(folderId, tempPhotoId, uri)
                updateLocalPhoto(folderId, PhotoItem(tempPhotoId, originalName, PhotoSyncStatus.Uploading, uri.toString(), thumbnailPath))

                apiClient.registerDevice(
                    deviceUuid = deviceUuid,
                    deviceName = deviceName,
                    appVersion = APP_VERSION,
                )
                val uploadResult = apiClient.uploadFile(
                    deviceUuid = deviceUuid,
                    albumName = folder.name,
                    originalName = originalName,
                    mimeType = mimeType,
                    sizeBytes = fileBytes.size.toLong(),
                    sha256 = sha256,
                    createdAtIso = Instant.now().toString(),
                    fileBytes = fileBytes,
                )
                updateLocalPhoto(
                    folderId,
                    PhotoItem(
                        tempPhotoId,
                        originalName,
                        PhotoSyncStatus.Synced,
                        uri.toString(),
                        thumbnailPath,
                        uploadResult.serverFileId,
                        uploadResult.relativePath,
                        mimeType,
                    ),
                )
                applyCleanupPolicy(uri, folderId)
                refresh()
            }
        }
            .onFailure { error -> Log.e(TAG, "Upload failed for folderId=$folderId uri=$uri", error) }
    }

    private fun getOrCreateDeviceUuid(): String {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val existing = preferences.getString(KEY_DEVICE_UUID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_UUID, generated).apply()
        return generated
    }

    companion object {
        private const val TAG = "PhotoSyncRepo"
        private const val PREFERENCES_NAME = "photosync"
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val KEY_LOCAL_FOLDERS = "local_folders"
        private const val KEY_LOCAL_PHOTOS = "local_photos"
        private const val KEY_FOLDER_POLICIES = "folder_policies"
        private const val APP_VERSION = "0.1.0"
    }

    private fun loadLocalFolders(): Map<String, FolderDetail> {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val raw = preferences.getString(KEY_LOCAL_FOLDERS, null).orEmpty()
        if (raw.isBlank()) return emptyMap()

        return runCatching {
            val items = JSONArray(raw)
            buildMap {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val id = item.getString("id")
                    put(
                        id,
                        FolderDetail(
                            id = id,
                            name = item.getString("name"),
                            photos = emptyList(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun loadLocalPhotos(): Map<String, List<PhotoItem>> {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val raw = preferences.getString(KEY_LOCAL_PHOTOS, null).orEmpty()
        if (raw.isBlank()) return emptyMap()

        return runCatching {
            val foldersArray = JSONArray(raw)
            buildMap {
                for (folderIndex in 0 until foldersArray.length()) {
                    val folderObject = foldersArray.getJSONObject(folderIndex)
                    val folderId = folderObject.getString("folder_id")
                    val photosArray = folderObject.getJSONArray("photos")
                    val photos = buildList {
                        for (photoIndex in 0 until photosArray.length()) {
                            val photoObject = photosArray.getJSONObject(photoIndex)
                            add(
                                PhotoItem(
                                    id = photoObject.getString("id"),
                                    title = photoObject.getString("title"),
                                    status = PhotoSyncStatus.valueOf(photoObject.getString("status")),
                                    localUri = photoObject.optString("local_uri", null),
                                    thumbnailPath = photoObject.optString("thumbnail_path", null),
                                    serverFileId = photoObject.optIntOrNull("server_file_id"),
                                    serverRelativePath = photoObject.optStringOrNull("server_relative_path"),
                                    mimeType = photoObject.optStringOrNull("mime_type"),
                                ),
                            )
                        }
                    }
                    put(folderId, photos)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun loadFolderPolicies(): Map<String, PhotoCleanupPolicy> {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val raw = preferences.getString(KEY_FOLDER_POLICIES, null).orEmpty()
        if (raw.isBlank()) return emptyMap()

        return runCatching {
            val items = JSONArray(raw)
            buildMap {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    put(
                        item.getString("folder_id"),
                        PhotoCleanupPolicy.valueOf(item.getString("policy")),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun loadLocalPhotosForFolder(folderId: String): List<PhotoItem> = localPhotos.value[folderId].orEmpty()

    private fun persistLocalFolders() {
        val payload = JSONArray()
        localFolders.value.values.forEach { folder ->
            payload.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name),
            )
        }

        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCAL_FOLDERS, payload.toString())
            .apply()
    }

    private fun persistLocalPhotos() {
        val payload = JSONArray()
        localPhotos.value.forEach { (folderId, photos) ->
            val photosArray = JSONArray()
            photos.forEach { photo ->
                photosArray.put(
                    JSONObject()
                        .put("id", photo.id)
                        .put("title", photo.title)
                        .put("status", photo.status.name)
                        .put("local_uri", photo.localUri ?: JSONObject.NULL)
                        .put("thumbnail_path", photo.thumbnailPath ?: JSONObject.NULL)
                        .put("server_file_id", photo.serverFileId ?: JSONObject.NULL)
                        .put("server_relative_path", photo.serverRelativePath ?: JSONObject.NULL)
                        .put("mime_type", photo.mimeType ?: JSONObject.NULL)
                )
            }
            payload.put(
                JSONObject()
                    .put("folder_id", folderId)
                    .put("photos", photosArray),
            )
        }

        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCAL_PHOTOS, payload.toString())
            .apply()
    }

    private fun persistFolderPolicies() {
        val payload = JSONArray()
        folderPolicies.value.forEach { (folderId, policy) ->
            payload.put(
                JSONObject()
                    .put("folder_id", folderId)
                    .put("policy", policy.name),
            )
        }

        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_POLICIES, payload.toString())
            .apply()
    }

    private fun upsertLocalFolder(summary: FolderSummary, detail: FolderDetail) {
        localFolders.value = localFolders.value + (summary.id to detail)
        folderDetails.value = folderDetails.value + (detail.id to detail)
        folders.value = listOf(summary) + folders.value.filterNot { it.id == summary.id }
        persistLocalFolders()
    }

    private fun restoreLocalState() {
        val summaries = localFolders.value.values.map { folder ->
            val photos = localPhotos.value[folder.id].orEmpty()
            folder.copy(photos = photos).toSummary()
        }
        val details = localFolders.value.mapValues { (id, folder) ->
            folder.copy(photos = localPhotos.value[id].orEmpty())
        }

        folders.value = summaries
        folderDetails.value = details
        stats.value = DashboardStats(
            totalFolders = summaries.size,
            totalPhotos = summaries.sumOf { it.photoCount },
            syncedPhotos = summaries.sumOf { it.syncedCount },
            pendingPhotos = summaries.sumOf { it.pendingCount },
            failedPhotos = summaries.sumOf { it.failedCount },
        )
    }

    private fun updateLocalPhoto(folderId: String, photo: PhotoItem) {
        localPhotos.value = localPhotos.value.toMutableMap().apply {
            val current = get(folderId).orEmpty().toMutableList()
            val existingIndex = current.indexOfFirst { it.id == photo.id }
            if (existingIndex >= 0) {
                current[existingIndex] = photo
            } else {
                current.add(photo)
            }
            put(folderId, current)
        }
        persistLocalPhotos()
        folderDetails.value = folderDetails.value.toMutableMap().apply {
            val currentFolder = get(folderId) ?: return@apply
            val photos = localPhotos.value[folderId].orEmpty()
            put(folderId, currentFolder.copy(photos = photos))
        }
    }

    private fun FolderDetail.previewThumbnailPaths(): List<String> = photos.previewThumbnailPaths()

    private fun FolderDetail.toSummary(): FolderSummary = FolderSummary(
        id = id,
        name = name,
        photoCount = photos.size,
        syncedCount = photos.count { it.status == PhotoSyncStatus.Synced },
        pendingCount = photos.count {
            it.status == PhotoSyncStatus.Pending ||
                it.status == PhotoSyncStatus.Uploading ||
                it.status == PhotoSyncStatus.RemoteOnly
        },
        failedCount = photos.count { it.status == PhotoSyncStatus.Failed },
        statusLabel = when {
            photos.any { it.status == PhotoSyncStatus.Uploading } -> "Uploading"
            photos.any { it.status == PhotoSyncStatus.Failed } -> "Failed"
            photos.any { it.status == PhotoSyncStatus.RemoteOnly } -> "On server"
            photos.isEmpty() -> "Pending sync"
            else -> "All synced"
        },
        previewThumbnailPaths = previewThumbnailPaths(),
    )

    private fun List<PhotoItem>.previewThumbnailPaths(): List<String> =
        mapNotNull { it.thumbnailPath }
            .filter { it.isNotBlank() }
            .take(3)

    private fun mergePhotos(
        previousPhotos: List<PhotoItem>,
        localPhotos: List<PhotoItem>,
        serverFiles: List<FileItemDto>,
        folderId: String,
    ): List<PhotoItem> {
        val merged = linkedMapOf<String, PhotoItem>()
        localPhotos.forEach { photo -> merged[photo.id] = photo }
        previousPhotos.forEach { photo ->
            if (photo.status != PhotoSyncStatus.RemoteOnly && merged[photo.id] == null) {
                merged[photo.id] = photo
            }
        }
        serverFiles.forEach { serverFile ->
            val existing = merged.values.firstOrNull { it.serverFileId == serverFile.id }
            if (existing != null) {
                merged[existing.id] = existing.copy(
                    serverFileId = serverFile.id,
                    serverRelativePath = serverFile.relativePath,
                    mimeType = serverFile.mimeType,
                )
            } else {
                val photoId = "server-${serverFile.id}"
                val previewPath = ensureServerPreview(folderId, serverFile)
                merged[photoId] = PhotoItem(
                    id = photoId,
                    title = serverFile.originalName,
                    status = PhotoSyncStatus.RemoteOnly,
                    localUri = null,
                    thumbnailPath = previewPath,
                    serverFileId = serverFile.id,
                    serverRelativePath = serverFile.relativePath,
                    mimeType = serverFile.mimeType,
                )
            }
        }
        return merged.values.toList()
    }

    private fun effectivePolicy(folderId: String): PhotoCleanupPolicy {
        return folderPolicies.value[folderId] ?: preferencesStore.getGlobalPhotoCleanupPolicy()
    }

    private fun applyCleanupPolicy(uri: Uri, folderId: String) {
        when (effectivePolicy(folderId)) {
            PhotoCleanupPolicy.Keep -> Unit
            PhotoCleanupPolicy.Compress -> runCatching {
                compressLocalCopy(uri, folderId)
            }.onFailure { error ->
                Log.e(TAG, "Compress policy failed for folderId=$folderId uri=$uri", error)
            }
            PhotoCleanupPolicy.Delete -> runCatching {
                if (uri.scheme == "content") {
                    appContext.contentResolver.delete(uri, null, null)
                }
            }.onFailure { error ->
                Log.e(TAG, "Delete policy failed for folderId=$folderId uri=$uri", error)
            }
        }
    }

    private fun compressLocalCopy(uri: Uri, folderId: String) {
        val source = appContext.contentResolver.openInputStream(uri) ?: return
        val compressedDir = File(appContext.filesDir, "compressed/$folderId")
        compressedDir.mkdirs()
        val outputFile = File(compressedDir, "${System.currentTimeMillis()}.jpg")
        source.use { input ->
            val bitmap = decodeBitmap(input) ?: return
            FileOutputStream(outputFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)
            }
        }
    }

    private fun decodeBitmap(inputStream: InputStream): Bitmap? {
        return runCatching {
            val bytes = inputStream.readBytes()
            val temp = File.createTempFile("photosync", ".tmp", appContext.cacheDir)
            temp.writeBytes(bytes)
            val source = ImageDecoder.createSource(temp)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setTargetSize(1600, 1600)
                decoder.isMutableRequired = false
            }
            temp.delete()
            bitmap
        }.getOrNull()
    }

    private fun ensureThumbnail(folderId: String, photoId: String, uri: Uri): String? {
        return runCatching {
            val thumbDir = File(appContext.filesDir, "thumbnails/$folderId")
            thumbDir.mkdirs()
            val thumbFile = File(thumbDir, "$photoId.jpg")
            val source = ImageDecoder.createSource(appContext.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setTargetSize(320, 320)
                decoder.isMutableRequired = false
            }
            FileOutputStream(thumbFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            }
            thumbFile.absolutePath
        }.getOrNull()
    }

    private fun ensureThumbnailFromFile(folderId: String, photoId: String, file: File): String? {
        return runCatching {
            val thumbDir = File(appContext.filesDir, "thumbnails/$folderId")
            thumbDir.mkdirs()
            val thumbFile = File(thumbDir, "$photoId.jpg")
            val source = ImageDecoder.createSource(file)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setTargetSize(320, 320)
                decoder.isMutableRequired = false
            }
            FileOutputStream(thumbFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            }
            thumbFile.absolutePath
        }.getOrNull()
    }

    private fun ensureServerPreview(folderId: String, serverFile: FileItemDto): String? {
        return runCatching {
            val previewDir = File(appContext.filesDir, "server_previews/$folderId")
            previewDir.mkdirs()
            val previewFile = File(previewDir, "${serverFile.id}.jpg")
            if (!previewFile.exists()) {
                previewFile.writeBytes(apiClient.downloadPreview(serverFile.id))
            }
            previewFile.absolutePath
        }.getOrElse { error ->
            Log.e(TAG, "Server preview download failed for fileId=${serverFile.id}", error)
            null
        }
    }

    override suspend fun deletePhoto(folderId: String, photoId: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val deletedPhoto = localPhotos.value[folderId].orEmpty().firstOrNull { it.id == photoId }
                localPhotos.value = localPhotos.value.toMutableMap().apply {
                    val current = get(folderId).orEmpty().filterNot { it.id == photoId }
                    put(folderId, current)
                }
                persistLocalPhotos()
                folderDetails.value = folderDetails.value.toMutableMap().apply {
                    val currentFolder = get(folderId) ?: return@apply
                    put(folderId, currentFolder.copy(photos = localPhotos.value[folderId].orEmpty()))
                }
                restoreLocalState()
                deletedPhoto?.thumbnailPath?.let { path ->
                    runCatching { File(path).delete() }
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Delete photo failed for folderId=$folderId photoId=$photoId", error)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }

        return uri.lastPathSegment
    }
}

private fun ByteArray.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}

private object StoragePathResolverSafeName {
    private val invalidChars = Regex("""[\\/:*?"<>|]""")

    fun make(value: String): String {
        val safe = value.trim().replace(invalidChars, "_")
        return safe.ifBlank { "downloaded-photo.jpg" }
    }
}

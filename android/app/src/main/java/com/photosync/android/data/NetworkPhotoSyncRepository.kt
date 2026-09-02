package com.photosync.android.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.photosync.android.data.remote.FileItemDto
import com.photosync.android.domain.model.DashboardStats
import com.photosync.android.domain.model.FolderDetail
import com.photosync.android.domain.model.FolderSummary
import com.photosync.android.domain.model.GoogleAccount
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import com.photosync.android.domain.model.ConnectionStatus
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
    private val operationMutex = Mutex()
    private val cacheNamespace: String get() = "photosync_v2_" + apiClient.deviceUuid()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stats = MutableStateFlow(DashboardStats())
    private val folders = MutableStateFlow<List<FolderSummary>>(emptyList())
    private val folderDetails = MutableStateFlow<Map<String, FolderDetail>>(emptyMap())
    private val localFolders = MutableStateFlow(loadLocalFolders())
    private val localPhotos = MutableStateFlow(loadLocalPhotos())
    private val folderPolicies = MutableStateFlow(loadFolderPolicies())
    private val serverUrl = MutableStateFlow(apiClient.currentBaseUrl())
    private val googleAccount = MutableStateFlow<GoogleAccount?>(null)
    private val deviceUuid: String get() = apiClient.deviceUuid()
    private val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    init {
        restoreLocalState()
        repositoryScope.launch {
            runCatching { refresh() }
                .onFailure { error -> Log.e(TAG, "Initial refresh failed", error) }
        }
    }

    override suspend fun addFolder(name: String) = operationMutex.withLock { addFolderInternal(name) }
    override suspend fun refresh() = operationMutex.withLock { refreshInternal() }
    override suspend fun updateServerUrl(serverUrl: String) = operationMutex.withLock { updateServerUrlInternal(serverUrl) }
    override suspend fun updateFolderPhotoCleanupPolicy(folderId: String, policy: PhotoCleanupPolicy?) = operationMutex.withLock { updateFolderPhotoCleanupPolicyInternal(folderId, policy) }
    override suspend fun downloadPhoto(folderId: String, photoId: String) = operationMutex.withLock { downloadPhotoInternal(folderId, photoId) }
    override suspend fun uploadToFolder(folderId: String, uri: Uri) = operationMutex.withLock { uploadToFolderInternal(folderId, uri) }
    override suspend fun deletePhoto(folderId: String, photoId: String) = operationMutex.withLock { deletePhotoInternal(folderId, photoId) }
    override suspend fun signInWithGoogle(idToken: String) = operationMutex.withLock {
        withContext(Dispatchers.IO) { googleAccount.value = apiClient.signInWithGoogle(idToken) }
        refreshInternal()
    }
    override suspend fun signOutFromGoogle() = operationMutex.withLock {
        withContext(Dispatchers.IO) { apiClient.signOutFromGoogle() }
        googleAccount.value = null
        refreshInternal()
    }

    override fun observeServerUrl(): Flow<String> = serverUrl.asStateFlow()

    override fun observeGlobalPhotoCleanupPolicy(): Flow<PhotoCleanupPolicy> =
        preferencesStore.observeGlobalPhotoCleanupPolicy()

    override fun observeFolderPhotoCleanupPolicy(folderId: String): Flow<PhotoCleanupPolicy?> =
        folderPolicies.asStateFlow().map { policies -> policies[folderId] }

    override fun observeStats(): Flow<DashboardStats> = stats.asStateFlow()

    override fun observeFolders(): Flow<List<FolderSummary>> = folders.asStateFlow()
    override fun observeGoogleAccount(): Flow<GoogleAccount?> = googleAccount.asStateFlow()

    override fun observeFolder(folderId: String): Flow<FolderDetail?> = folderDetails.asStateFlow().map { details ->
        details[folderId]
    }

    private suspend fun addFolderInternal(name: String) {
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

                refreshInternal()
            }
        }
            .onFailure { error -> Log.e(TAG, "Add folder failed: $name", error) }
    }

    private suspend fun refreshInternal() {
        stats.value = stats.value.copy(connectionStatus = ConnectionStatus.Connecting)
        runCatching {
            withContext(Dispatchers.IO) {
                apiClient.registerDevice(
                    deviceUuid = deviceUuid,
                    deviceName = deviceName,
                    appVersion = APP_VERSION,
                )
                googleAccount.value = apiClient.googleAccount()
                val summary = apiClient.getSummary()
                val visibleDevices = apiClient.getDevices()
                val serverAlbums = visibleDevices.flatMap { device -> apiClient.getAlbums(device.deviceUuid) }
                    .distinctBy { it.name }
                val serverFiles = visibleDevices.flatMap { device -> apiClient.getFiles(device.id) }
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
                    val folderSummary = detail.toSummary()
                    details[folderId] = detail.copy(id = folderId)
                    summaries[folderId] = folderSummary
                }

                localFolders.value = details.mapValues { (_, folder) -> folder.copy(photos = emptyList()) }
                localPhotos.value = details.mapValues { (_, folder) -> folder.photos }
                persistLocalFolders()
                persistLocalPhotos()
                folderDetails.value = details
                folders.value = summaries.values.toList()
                stats.value = DashboardStats(
                    totalFolders = folders.value.size,
                    totalPhotos = maxOf(summary.fileCount, folders.value.sumOf { it.photoCount }),
                    syncedPhotos = folders.value.sumOf { it.syncedCount },
                    pendingPhotos = folders.value.sumOf { it.pendingCount },
                    failedPhotos = folders.value.sumOf { it.failedCount },
                    connectionStatus = ConnectionStatus.Online,
                )
            }
        }
            .onFailure { error ->
                Log.e(TAG, "Refresh failed", error)
                restoreLocalState()
                stats.value = stats.value.copy(connectionStatus = ConnectionStatus.Offline)
            }
    }

    private suspend fun updateServerUrlInternal(serverUrl: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val normalized = ServerAddress.normalize(serverUrl)
                apiClient.updateBaseUrl(normalized)
                preferencesStore.updateServerUrl(normalized)
                this@NetworkPhotoSyncRepository.serverUrl.value = apiClient.currentBaseUrl()
                googleAccount.value = null
                localFolders.value = loadLocalFolders()
                localPhotos.value = loadLocalPhotos()
                folderPolicies.value = loadFolderPolicies()
                stats.value = DashboardStats()
                restoreLocalState()
                refreshInternal()
            }
        }
            .onFailure { error -> Log.e(TAG, "Update server URL failed", error) }
    }

    override suspend fun updateGlobalPhotoCleanupPolicy(policy: PhotoCleanupPolicy) {
        withContext(Dispatchers.IO) {
            preferencesStore.updateGlobalPhotoCleanupPolicy(policy)
        }
    }

    private suspend fun updateFolderPhotoCleanupPolicyInternal(folderId: String, policy: PhotoCleanupPolicy?) {
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

    private suspend fun downloadPhotoInternal(folderId: String, photoId: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val photo = localPhotos.value[folderId].orEmpty().firstOrNull { it.id == photoId }
                    ?: folderDetails.value[folderId]?.photos.orEmpty().firstOrNull { it.id == photoId }
                    ?: return@withContext
                val serverFileId = photo.serverFileId ?: return@withContext
                val bytes = apiClient.downloadFile(serverFileId)
                val downloadsDir = File(appContext.filesDir, "$cacheNamespace/downloads/$folderId")
                downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, "$serverFileId-" + StoragePathResolverSafeName.make(photo.title))
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

    private suspend fun uploadToFolderInternal(folderId: String, uri: Uri): Boolean {
        var attemptedPhotoId: String? = null
        return runCatching {
            withContext(Dispatchers.IO) {
                val folder = folderDetails.value[folderId] ?: error("Upload folder was not found.")
                val fileBytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Shared media could not be opened.")
                val mimeType = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
                val originalName = resolveDisplayName(uri) ?: "upload-${System.currentTimeMillis()}"
                val sha256 = fileBytes.sha256()
                val tempPhotoId = UUID.randomUUID().toString()
                attemptedPhotoId = tempPhotoId

                val thumbnailPath = ensureThumbnail(folderId, tempPhotoId, uri)
                updateLocalPhoto(folderId, PhotoItem(tempPhotoId, originalName, PhotoSyncStatus.Uploading, uri.toString(), thumbnailPath))
                restoreLocalState()

                apiClient.registerDevice(
                    deviceUuid = deviceUuid,
                    deviceName = deviceName,
                    appVersion = APP_VERSION,
                )
                apiClient.createAlbum(deviceUuid, folder.name)
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
                refreshInternal()
            }
        }
            .onFailure { error ->
                if (error is CancellationException) throw error
                attemptedPhotoId?.let { id ->
                    localPhotos.value[folderId]?.firstOrNull { it.id == id }?.let {
                        updateLocalPhoto(folderId, it.copy(status = PhotoSyncStatus.Failed))
                    }
                }
                restoreLocalState()
                stats.value = stats.value.copy(connectionStatus = ConnectionStatus.Offline)
                Log.e(TAG, "Upload failed for folderId=$folderId", error)
            }
            .isSuccess
    }

    companion object {
        private const val TAG = "PhotoSyncRepo"
        private const val KEY_LOCAL_FOLDERS = "local_folders"
        private const val KEY_LOCAL_PHOTOS = "local_photos"
        private const val KEY_FOLDER_POLICIES = "folder_policies"
        private const val APP_VERSION = com.photosync.android.BuildConfig.VERSION_NAME
    }

    private fun loadLocalFolders(): Map<String, FolderDetail> {
        val preferences = appContext.getSharedPreferences(cacheNamespace, Context.MODE_PRIVATE)
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
        val preferences = appContext.getSharedPreferences(cacheNamespace, Context.MODE_PRIVATE)
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
                                    localUri = photoObject.optStringOrNull("local_uri"),
                                    thumbnailPath = photoObject.optStringOrNull("thumbnail_path"),
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
        val preferences = appContext.getSharedPreferences(cacheNamespace, Context.MODE_PRIVATE)
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

        appContext.getSharedPreferences(cacheNamespace, Context.MODE_PRIVATE)
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

        appContext.getSharedPreferences(cacheNamespace, Context.MODE_PRIVATE)
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

        appContext.getSharedPreferences(cacheNamespace, Context.MODE_PRIVATE)
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
            connectionStatus = stats.value.connectionStatus,
        )
    }

    private fun updateLocalPhoto(folderId: String, photo: PhotoItem) {
        localPhotos.value = localPhotos.value.toMutableMap().apply {
            val current = get(folderId).orEmpty().filterNot {
                photo.serverFileId != null && it.serverFileId == photo.serverFileId && it.id != photo.id
            }.toMutableList()
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
        syncedCount = photos.count { it.status == PhotoSyncStatus.Synced || it.status == PhotoSyncStatus.RemoteOnly },
        pendingCount = photos.count {
            it.status == PhotoSyncStatus.Pending ||
                it.status == PhotoSyncStatus.Uploading
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
        val compressedDir = File(appContext.filesDir, "$cacheNamespace/compressed/$folderId")
        compressedDir.mkdirs()
        val outputFile = File(compressedDir, "${System.currentTimeMillis()}.jpg")
        source.use { input ->
            val bitmap = decodeBitmap(input) ?: return
            FileOutputStream(outputFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output)
            }
        }
    }

    private fun decodeBitmap(inputStream: InputStream): Bitmap? =
        runCatching { decodePhotoBitmap(inputStream, 1600) }.getOrNull()

    private fun ensureThumbnail(folderId: String, photoId: String, uri: Uri): String? {
        return runCatching {
            val thumbDir = File(appContext.filesDir, "$cacheNamespace/thumbnails/$folderId")
            thumbDir.mkdirs()
            val thumbFile = File(thumbDir, "$photoId.jpg")
            val bitmap = appContext.contentResolver.openInputStream(uri)?.use { decodePhotoBitmap(it, 320) } ?: return@runCatching null
            FileOutputStream(thumbFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            }
            thumbFile.absolutePath
        }.getOrNull()
    }

    private fun ensureThumbnailFromFile(folderId: String, photoId: String, file: File): String? {
        return runCatching {
            val thumbDir = File(appContext.filesDir, "$cacheNamespace/thumbnails/$folderId")
            thumbDir.mkdirs()
            val thumbFile = File(thumbDir, "$photoId.jpg")
            val bitmap = file.inputStream().use { decodePhotoBitmap(it, 320) } ?: return@runCatching null
            FileOutputStream(thumbFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            }
            thumbFile.absolutePath
        }.getOrNull()
    }

    private fun ensureServerPreview(folderId: String, serverFile: FileItemDto): String? {
        return runCatching {
            val previewDir = File(appContext.filesDir, "$cacheNamespace/server_previews/$folderId")
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

    private suspend fun deletePhotoInternal(folderId: String, photoId: String) {
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

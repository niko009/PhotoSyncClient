package com.photosync.android.data

import android.net.Uri
import com.photosync.android.domain.model.DashboardStats
import com.photosync.android.domain.model.FolderDetail
import com.photosync.android.domain.model.FolderSummary
import com.photosync.android.domain.model.GoogleAccount
import com.photosync.android.domain.model.PhotoItem
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.model.PhotoSyncStatus
import com.photosync.android.domain.repository.PhotoSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakePhotoSyncRepository(
    seedFolders: List<FolderRecord> = sampleFolders(),
    private val uploadSucceeds: Boolean = true,
) : PhotoSyncRepository {

    private val folders = MutableStateFlow(seedFolders)
    private val serverUrl = MutableStateFlow("http://127.0.0.1:5187")
    private val globalPolicy = MutableStateFlow(PhotoCleanupPolicy.Keep)
    private val folderPolicies = MutableStateFlow<Map<String, PhotoCleanupPolicy>>(emptyMap())
    private val googleAccount = MutableStateFlow<GoogleAccount?>(null)

    override fun observeGlobalPhotoCleanupPolicy(): Flow<PhotoCleanupPolicy> = globalPolicy
    override fun observeFolderPhotoCleanupPolicy(folderId: String): Flow<PhotoCleanupPolicy?> =
        folderPolicies.map { it[folderId] }
    override suspend fun updateGlobalPhotoCleanupPolicy(policy: PhotoCleanupPolicy) { globalPolicy.value = policy }
    override suspend fun updateFolderPhotoCleanupPolicy(folderId: String, policy: PhotoCleanupPolicy?) {
        folderPolicies.update { current -> if (policy == null) current - folderId else current + (folderId to policy) }
    }
    override suspend fun deletePhoto(folderId: String, photoId: String) {
        folders.update { records ->
            records.map { if (it.id == folderId) it.copy(photos = it.photos.filterNot { photo -> photo.id == photoId }) else it }
        }
    }

    override fun observeServerUrl(): Flow<String> = serverUrl
    override fun observeGoogleAccount(): Flow<GoogleAccount?> = googleAccount
    override suspend fun signInWithGoogle(idToken: String) {
        googleAccount.value = GoogleAccount("family@example.test", "Family User", 1)
    }
    override suspend fun signOutFromGoogle() { googleAccount.value = null }

    override fun observeStats(): Flow<DashboardStats> = folders.map { records ->
        DashboardStats(
            totalFolders = records.size,
            totalPhotos = records.sumOf { it.photos.size },
            syncedPhotos = records.sumOf { record ->
                record.photos.count { it.status == PhotoSyncStatus.Synced }
            },
            pendingPhotos = records.sumOf { record ->
                record.photos.count {
                    it.status == PhotoSyncStatus.Pending ||
                        it.status == PhotoSyncStatus.Uploading ||
                        it.status == PhotoSyncStatus.RemoteOnly
                }
            },
            failedPhotos = records.sumOf { record ->
                record.photos.count { it.status == PhotoSyncStatus.Failed }
            },
        )
    }

    override fun observeFolders(): Flow<List<FolderSummary>> = folders.map { records ->
        records.map { it.toSummary() }
    }

    override fun observeFolder(folderId: String): Flow<FolderDetail?> = folders.map { records ->
        records.firstOrNull { it.id == folderId }?.toDetail()
    }

    override suspend fun refresh() = Unit

    override suspend fun updateServerUrl(serverUrl: String) {
        this.serverUrl.value = serverUrl
    }

    override suspend fun addFolder(name: String) {
        folders.update { current ->
            current + newFolderRecord(
                index = current.size + 1,
                name = name,
            )
        }
    }

    override suspend fun uploadToFolder(folderId: String, uri: Uri): Boolean {
        if (!uploadSucceeds) return false
        folders.update { current ->
            current.map { record ->
                if (record.id != folderId) {
                    record
                } else {
                    record.copy(
                        photos = listOf(
                            PhotoItem(
                                id = "${record.id}-upload-${record.photos.size + 1}",
                                title = uri.lastPathSegment ?: "Picked file",
                                status = PhotoSyncStatus.Uploading,
                            )
                        ) + record.photos
                    )
                }
            }
        }
        return true
    }

    override suspend fun downloadPhoto(folderId: String, photoId: String) = Unit

    companion object {
        fun sampleFolders(): List<FolderRecord> = listOf(
            FolderRecord(
                id = "folder-1",
                name = "Camera Roll",
                photos = buildPhotos(
                    folderId = "folder-1",
                    statuses = listOf(
                        PhotoSyncStatus.Synced,
                        PhotoSyncStatus.Synced,
                        PhotoSyncStatus.Uploading,
                        PhotoSyncStatus.Pending,
                        PhotoSyncStatus.Synced,
                        PhotoSyncStatus.Failed,
                    ),
                ),
            ),
            FolderRecord(
                id = "folder-2",
                name = "Vacation 2026",
                photos = buildPhotos(
                    folderId = "folder-2",
                    statuses = listOf(
                        PhotoSyncStatus.Synced,
                        PhotoSyncStatus.Synced,
                        PhotoSyncStatus.Synced,
                        PhotoSyncStatus.Pending,
                        PhotoSyncStatus.Pending,
                        PhotoSyncStatus.Uploading,
                        PhotoSyncStatus.Synced,
                        PhotoSyncStatus.Synced,
                    ),
                ),
            ),
            FolderRecord(
                id = "folder-3",
                name = "Receipts",
                photos = buildPhotos(
                    folderId = "folder-3",
                    statuses = listOf(
                        PhotoSyncStatus.Failed,
                        PhotoSyncStatus.Pending,
                        PhotoSyncStatus.Synced,
                        PhotoSyncStatus.Synced,
                    ),
                ),
            ),
        )

        private fun newFolderRecord(index: Int, name: String): FolderRecord = FolderRecord(
            id = "folder-$index",
            name = name,
            photos = buildPhotos(
                folderId = "folder-$index",
                statuses = listOf(
                    PhotoSyncStatus.Pending,
                    PhotoSyncStatus.Pending,
                    PhotoSyncStatus.Uploading,
                    PhotoSyncStatus.Synced,
                ),
            ),
        )

        private fun buildPhotos(
            folderId: String,
            statuses: List<PhotoSyncStatus>,
        ): List<PhotoItem> = statuses.mapIndexed { index, status ->
            PhotoItem(
                id = "$folderId-photo-$index",
                title = "IMG_${folderId.takeLast(1)}${index + 1}",
                status = status,
            )
        }
    }
}

data class FolderRecord(
    val id: String,
    val name: String,
    val photos: List<PhotoItem>,
)

internal fun FolderRecord.toSummary(): FolderSummary {
    val synced = photos.count { it.status == PhotoSyncStatus.Synced }
    val pending = photos.count {
        it.status == PhotoSyncStatus.Pending ||
            it.status == PhotoSyncStatus.Uploading ||
            it.status == PhotoSyncStatus.RemoteOnly
    }
    val failed = photos.count { it.status == PhotoSyncStatus.Failed }
    val label = when {
        failed > 0 -> "$failed failed"
        pending > 0 -> "$pending pending"
        else -> "All synced"
    }

    return FolderSummary(
        id = id,
        name = name,
        photoCount = photos.size,
        syncedCount = synced,
        pendingCount = pending,
        failedCount = failed,
        statusLabel = label,
    )
}

internal fun FolderRecord.toDetail(): FolderDetail = FolderDetail(
    id = id,
    name = name,
    photos = photos,
)

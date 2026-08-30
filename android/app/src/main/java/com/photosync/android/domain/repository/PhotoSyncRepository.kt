package com.photosync.android.domain.repository

import android.net.Uri
import com.photosync.android.domain.model.DashboardStats
import com.photosync.android.domain.model.FolderDetail
import com.photosync.android.domain.model.PhotoCleanupPolicy
import com.photosync.android.domain.model.FolderSummary
import kotlinx.coroutines.flow.Flow

interface PhotoSyncRepository {
    fun observeServerUrl(): Flow<String>
    fun observeGlobalPhotoCleanupPolicy(): Flow<PhotoCleanupPolicy>
    fun observeFolderPhotoCleanupPolicy(folderId: String): Flow<PhotoCleanupPolicy?>
    fun observeStats(): Flow<DashboardStats>
    fun observeFolders(): Flow<List<FolderSummary>>
    fun observeFolder(folderId: String): Flow<FolderDetail?>
    suspend fun refresh()
    suspend fun updateServerUrl(serverUrl: String)
    suspend fun updateGlobalPhotoCleanupPolicy(policy: PhotoCleanupPolicy)
    suspend fun updateFolderPhotoCleanupPolicy(folderId: String, policy: PhotoCleanupPolicy?)
    suspend fun addFolder(name: String)
    suspend fun uploadToFolder(folderId: String, uri: Uri)
    suspend fun downloadPhoto(folderId: String, photoId: String)
    suspend fun deletePhoto(folderId: String, photoId: String)
}

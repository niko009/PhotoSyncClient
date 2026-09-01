package com.photosync.android.domain.model

enum class ConnectionStatus { Unknown, Connecting, Online, Offline }

data class DashboardStats(
    val totalFolders: Int = 0,
    val totalPhotos: Int = 0,
    val syncedPhotos: Int = 0,
    val pendingPhotos: Int = 0,
    val failedPhotos: Int = 0,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Unknown,
)

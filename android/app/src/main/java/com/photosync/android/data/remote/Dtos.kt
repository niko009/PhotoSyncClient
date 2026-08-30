package com.photosync.android.data.remote

data class DeviceRegistrationDto(
    val deviceId: Int,
    val registered: Boolean,
)

data class ServerSummaryDto(
    val deviceCount: Int,
    val fileCount: Int,
    val photoCount: Int,
    val videoCount: Int,
    val bytesTotal: Long,
)

data class DeviceSummaryDto(
    val id: Int,
    val deviceUuid: String,
    val deviceName: String,
)

data class AlbumDto(
    val id: Int,
    val name: String,
    val serverFolderPath: String,
    val created: Boolean,
)

data class FileItemDto(
    val id: Int,
    val albumName: String,
    val originalName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val previewUrl: String,
    val downloadUrl: String,
)

data class FileUploadResultDto(
    val serverFileId: Int,
    val storedName: String,
    val relativePath: String,
)

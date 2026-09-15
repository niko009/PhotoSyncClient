package com.photosync.android.domain.model

data class DeviceIdentifiers(
    val deviceUuid: String = "",
    val serverDeviceId: Int? = null,
    val deviceName: String = "",
)

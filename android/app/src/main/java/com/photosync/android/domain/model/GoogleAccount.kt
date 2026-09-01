package com.photosync.android.domain.model

data class GoogleAccount(
    val email: String,
    val displayName: String,
    val linkedDevices: Int,
)

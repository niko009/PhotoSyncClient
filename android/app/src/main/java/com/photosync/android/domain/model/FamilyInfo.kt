package com.photosync.android.domain.model

data class FamilyInfo(
    val id: Int,
    val name: String,
    val role: String,
    val members: List<FamilyMember>,
    val pendingInvites: List<FamilyInvite>,
)

data class FamilyMember(
    val userId: Int,
    val email: String,
    val displayName: String?,
    val role: String,
    val isCurrentUser: Boolean,
)

data class FamilyInvite(
    val id: Int,
    val expectedEmail: String,
    val expiresAt: String,
    val status: String,
    val inviteUrl: String? = null,
)

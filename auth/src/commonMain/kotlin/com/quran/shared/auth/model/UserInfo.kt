package com.quran.shared.auth.model

import kotlinx.serialization.Serializable

@Serializable
data class UserInfo(
    val id: String,
    val name: String?,
    val email: String?,
    val photoUrl: String?
)

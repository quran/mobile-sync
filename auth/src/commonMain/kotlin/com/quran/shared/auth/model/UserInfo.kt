package com.quran.shared.auth.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class UserInfo(
    @SerialName("sub")
    val id: String,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("last_name")
    val lastName: String? = null,
    val name: String? = null,
    val email: String? = null,
    @SerialName("picture")
    val photoUrl: String? = null
) {
    val displayName: String? get() = name ?: run {
        val full = listOfNotNull(firstName, lastName).joinToString(" ").trim()
        if (full.isBlank()) null else full
    }
}

package com.quran.shared.auth.model

import io.ktor.util.decodeBase64String
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

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
        full.ifBlank { null }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJwt(token: String): UserInfo? {
            return try {
                val parts = token.split(".")
                if (parts.size < 2) return null

                val payload = Base64.decode(parts[1]).decodeToString()
                val jsonObject = json.parseToJsonElement(payload).jsonObject

                UserInfo(
                    id = jsonObject["sub"]?.jsonPrimitive?.content ?: "",
                    firstName = jsonObject["given_name"]?.jsonPrimitive?.content ?: jsonObject["first_name"]?.jsonPrimitive?.content,
                    lastName = jsonObject["family_name"]?.jsonPrimitive?.content ?: jsonObject["last_name"]?.jsonPrimitive?.content,
                    name = jsonObject["name"]?.jsonPrimitive?.content,
                    email = jsonObject["email"]?.jsonPrimitive?.content,
                    photoUrl = jsonObject["picture"]?.jsonPrimitive?.content
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

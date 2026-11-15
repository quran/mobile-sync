package com.quran.shared.pipeline.integration.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Where the fake serves the photos uploaded on the website: the backend's `AVATARS_HOST`. */
const val AVATARS_HOST = "https://avatars.example.invalid"

/**
 * The profile's `avatars`, built from [photoUrl] as the users backend's `avatarsBuilder`
 * (`packages/services/src/avatar.ts`) builds them.
 */
internal fun avatars(userId: String, photoUrl: String?): JsonObject = buildJsonObject {
    AVATAR_SIZES.forEach { size -> put(size, avatarUrl(userId, photoUrl, size)) }
}

private fun avatarUrl(userId: String, photoUrl: String?, size: String): String {
    if (photoUrl.isNullOrEmpty()) return ""
    if (photoUrl.startsWith("http", ignoreCase = true)) return photoUrl
    val parts = photoUrl.split('.')
    val directory = parts[0].ifEmpty { userId }
    val extension = parts.getOrNull(1) ?: "png"
    val version = parts.getOrNull(2) ?: "0"
    return "$AVATARS_HOST/events/banner/en/avatars/$directory/$size.$extension?v=$version"
}

private val AVATAR_SIZES = listOf("small", "medium", "large")

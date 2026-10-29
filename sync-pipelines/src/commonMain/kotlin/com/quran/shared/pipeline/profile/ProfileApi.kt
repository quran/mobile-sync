package com.quran.shared.pipeline.profile

import com.quran.shared.auth.model.FetchedProfile
import com.quran.shared.pipeline.SynchronizationEnvironment
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The profile endpoint answered [status], an error. */
internal class ProfileHttpException(val status: Int) : Exception("The profile request failed with HTTP $status")

/** The profile endpoint answered with something other than a profile. [reason] holds no response data. */
internal class UnexpectedProfileResponseException(reason: String) : Exception("Unexpected profile response: $reason")

/**
 * `GET /v1/users/profile` on the sync host: the users backend's `getUserProfile`, which reads the account live, so it
 * has the name and photo set on Quran.com since the user consented. It takes the sync requests' headers
 * (`x-auth-token`, `x-client-id`).
 */
internal class ProfileApi(
    private val httpClient: HttpClient,
    private val environment: SynchronizationEnvironment
) {
    /**
     * The signed-in account's profile, stamped [fetchedAt].
     *
     * @throws ProfileHttpException for an error status.
     * @throws UnexpectedProfileResponseException when the response isn't a profile.
     */
    suspend fun fetch(headers: Map<String, String>, fetchedAt: () -> Long): FetchedProfile {
        val response = httpClient.get("${environment.endPointURL}/v1/users/profile") {
            headers { headers.forEach { (key, value) -> append(key, value) } }
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw ProfileHttpException(response.status.value)
        return parseProfile(text, environment.avatarsHost, fetchedAt())
    }
}

/**
 * Reads the users API's envelope, `{success: true, data: {id, email, firstName, lastName, fullName, photoUrl, ...}}`.
 * The name is `fullName`, or the first and last names joined when it is empty; blank fields are null. The photo is
 * [profilePhotoUrl]'s.
 */
internal fun parseProfile(text: String, avatarsHost: String, fetchedAt: Long): FetchedProfile {
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: throw UnexpectedProfileResponseException("not a JSON object")
    if ((root["success"] as? JsonPrimitive)?.booleanOrNull == false) {
        throw UnexpectedProfileResponseException("success is false")
    }
    val profile = root["data"] as? JsonObject ?: throw UnexpectedProfileResponseException("no profile in data")
    val id = profile.text("id") ?: throw UnexpectedProfileResponseException("the profile has no id")
    val firstName = profile.text("firstName")
    val lastName = profile.text("lastName")
    return FetchedProfile(
        id = id,
        name = profile.text("fullName") ?: listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { null },
        firstName = firstName,
        lastName = lastName,
        email = profile.text("email"),
        photoUrl = profilePhotoUrl(profile.text("photoUrl"), id, avatarsHost),
        fetchedAt = fetchedAt
    )
}

/**
 * The URL of the profile's photo, from the profile's `photoUrl`, as the users backend's `avatarsBuilder` builds it
 * (`packages/services/src/avatar.ts`):
 * - none (null or blank): no photo;
 * - a full URL (it starts with `http`, as a social login's photo does): as it is;
 * - a photo uploaded on Quran.com: `photoUrl` is `<file name>.<extension>.<version>` (the file name usually empty), and
 *   the image is `{avatarsHost}/events/banner/en/avatars/{file name, else the user's id}/medium.{extension, else
 *   png}?v={version, else 0}`.
 *
 * It uses the medium size, 256 px, which covers the largest avatar the apps show (88 pt on iOS).
 */
internal fun profilePhotoUrl(photoUrl: String?, userId: String, avatarsHost: String): String? {
    if (photoUrl.isNullOrBlank()) return null
    if (photoUrl.startsWith("http", ignoreCase = true)) return photoUrl
    val parts = photoUrl.split('.')
    val directory = parts[0].ifEmpty { userId }
    val extension = parts.getOrNull(1) ?: DEFAULT_EXTENSION
    val version = parts.getOrNull(2) ?: DEFAULT_VERSION
    return "$avatarsHost/$AVATARS_PATH/$directory/$PHOTO_SIZE.$extension?v=$version"
}

/** A string field, or null when it is missing, not a string, or blank. */
private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/** The backend's `USER_AVATAR_URL_S3_PATH`. */
private const val AVATARS_PATH = "events/banner/en/avatars"
private const val PHOTO_SIZE = "medium"
private const val DEFAULT_EXTENSION = "png"
private const val DEFAULT_VERSION = "0"

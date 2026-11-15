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
        return parseProfile(text, fetchedAt())
    }
}

/**
 * Reads the users API's envelope, `{success: true, data: {id, email, firstName, lastName, fullName, avatars, ...}}`.
 * The name is `fullName`, or the first and last names joined when it is empty; blank fields are null. The photo is
 * [avatarUrl]'s.
 */
internal fun parseProfile(text: String, fetchedAt: Long): FetchedProfile {
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
        photoUrl = avatarUrl(profile["avatars"] as? JsonObject),
        fetchedAt = fetchedAt
    )
}

/**
 * The profile's photo: the backend's (`avatarsBuilder`) medium avatar, 256 px, which covers the largest avatar the apps
 * show (88 pt on iOS). It is an uploaded photo's URL or a social login's; only an absolute `http(s)` URL is a photo, as
 * the backend answers `""` for none and a relative path where its avatar host isn't configured.
 */
private fun avatarUrl(avatars: JsonObject?): String? =
    avatars?.text("medium")?.takeIf { url -> ABSOLUTE_URL_SCHEMES.any { url.startsWith(it, ignoreCase = true) } }

/** A string field, or null when it is missing, not a string, or blank. */
private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private val ABSOLUTE_URL_SCHEMES = listOf("https://", "http://")

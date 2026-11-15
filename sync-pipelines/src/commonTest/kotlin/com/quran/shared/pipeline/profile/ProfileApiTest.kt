package com.quran.shared.pipeline.profile

import com.quran.shared.auth.model.FetchedProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Reading the users API's profile response, with its medium avatar as the photo. */
class ProfileApiTest {
    @Test
    fun `a profile reads from the users API's envelope`() {
        val profile = parseProfile(
            """{"success":true,"data":{"id":"user-id","createdAt":"2022-12-06T06:16:20.229Z","email":"user@example.com",""" +
                """"firstName":"Muhammad","lastName":"Muhajir","username":"muhajir","fullName":"Muhammad Muhajir",""" +
                """"photoUrl":".png.1788192266","avatars":{"small":"$AVATARS/small.png?v=1788192266",""" +
                """"medium":"$AVATARS/medium.png?v=1788192266","large":"$AVATARS/large.png?v=1788192266"},""" +
                """"isAdmin":false,"isPasswordSet":true}}""",
            fetchedAt = 1_000L
        )

        assertEquals(
            FetchedProfile(
                id = "user-id",
                name = "Muhammad Muhajir",
                firstName = "Muhammad",
                lastName = "Muhajir",
                email = "user@example.com",
                photoUrl = "$AVATARS/medium.png?v=1788192266",
                fetchedAt = 1_000L
            ),
            profile
        )
    }

    @Test
    fun `blank and missing fields are null and the name falls back to the first and last names`() {
        val profile = parseProfile(
            """{"success":true,"data":{"id":"user-id","firstName":"Muhammad","lastName":"","fullName":"","photoUrl":""}}""",
            fetchedAt = 1_000L
        )

        assertEquals(FetchedProfile(id = "user-id", name = "Muhammad", firstName = "Muhammad", fetchedAt = 1_000L), profile)
    }

    @Test
    fun `a response that isn't a profile is rejected`() {
        listOf(
            "Bad Gateway",
            "[]",
            """{"success":true}""",
            """{"success":true,"data":null}""",
            """{"success":false,"data":{"id":"user-id"}}""",
            """{"success":true,"data":{"email":"user@example.com"}}""",
            """{"success":true,"data":{"id":42}}"""
        ).forEach { body ->
            assertFailsWith<UnexpectedProfileResponseException>(body) { parseProfile(body, fetchedAt = 0L) }
        }
    }

    @Test
    fun `a social login's photo is used as it is`() {
        val photo = "HTTPS://lh3.googleusercontent.com/a/photo"

        assertEquals(photo, photoUrl("""{"small":"$photo","medium":"$photo","large":"$photo"}"""))
    }

    @Test
    fun `the medium avatar is the photo`() {
        assertEquals(
            "http://avatars.example.com/user-id/medium.png?v=1",
            photoUrl("""{"small":"http://avatars.example.com/user-id/small.png?v=1",""" +
                """"medium":"http://avatars.example.com/user-id/medium.png?v=1"}""")
        )
    }

    @Test
    fun `no avatars or no medium avatar is no photo`() {
        assertNull(photoUrl(null), "no avatars")
        assertNull(photoUrl("null"), "null avatars")
        assertNull(photoUrl("\"https://example.com/a.png\""), "avatars not an object")
        assertNull(photoUrl("""{"small":"https://example.com/a.png"}"""), "no medium")
        assertNull(photoUrl("""{"medium":42}"""), "medium not a string")
        assertNull(photoUrl("""{"small":"","medium":"","large":""}"""), "the backend's none")
    }

    @Test
    fun `a relative avatar path is no photo`() {
        // The backend answers this where its avatar host isn't configured.
        assertNull(photoUrl("""{"medium":"./user-id/medium.png?v=1788192266"}"""))
        assertNull(photoUrl("""{"medium":"user-id/medium.png"}"""))
        assertNull(photoUrl("""{"medium":"ftp://example.com/medium.png"}"""))
    }

    /** The photo of a profile whose `avatars` is [avatars] (JSON), or that has none for null. */
    private fun photoUrl(avatars: String?): String? {
        val field = avatars?.let { ""","avatars":$it""" }.orEmpty()
        return parseProfile("""{"success":true,"data":{"id":"user-id"$field}}""", fetchedAt = 0L).photoUrl
    }

    private companion object {
        const val AVATARS = "https://s3.amazonaws.com/qreflect-bucket/events/banner/en/avatars/user-id"
    }
}

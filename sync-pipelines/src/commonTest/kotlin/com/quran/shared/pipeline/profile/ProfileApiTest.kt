package com.quran.shared.pipeline.profile

import com.quran.shared.auth.model.FetchedProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Reading the users API's profile response, and building the photo URL as the backend's `avatarsBuilder` does. */
class ProfileApiTest {
    @Test
    fun `a profile reads from the users API's envelope`() {
        val profile = parseProfile(
            """{"success":true,"data":{"id":"user-id","createdAt":"2022-12-06T06:16:20.229Z","email":"user@example.com",""" +
                """"firstName":"Muhammad","lastName":"Muhajir","username":"muhajir","fullName":"Muhammad Muhajir",""" +
                """"photoUrl":"https://lh3.googleusercontent.com/a/photo","isAdmin":false,"isPasswordSet":true}}""",
            AVATARS_HOST,
            fetchedAt = 1_000L
        )

        assertEquals(
            FetchedProfile(
                id = "user-id",
                name = "Muhammad Muhajir",
                firstName = "Muhammad",
                lastName = "Muhajir",
                email = "user@example.com",
                photoUrl = "https://lh3.googleusercontent.com/a/photo",
                fetchedAt = 1_000L
            ),
            profile
        )
    }

    @Test
    fun `blank and missing fields are null and the name falls back to the first and last names`() {
        val profile = parseProfile(
            """{"success":true,"data":{"id":"user-id","firstName":"Muhammad","lastName":"","fullName":"","photoUrl":""}}""",
            AVATARS_HOST,
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
            assertFailsWith<UnexpectedProfileResponseException>(body) { parseProfile(body, AVATARS_HOST, fetchedAt = 0L) }
        }
    }

    @Test
    fun `no photo is null`() {
        assertNull(profilePhotoUrl(null, "user-id", AVATARS_HOST))
        assertNull(profilePhotoUrl("", "user-id", AVATARS_HOST))
    }

    @Test
    fun `a full URL is used as it is`() {
        assertEquals("https://example.com/me.jpg", profilePhotoUrl("https://example.com/me.jpg", "user-id", AVATARS_HOST))
        assertEquals("HTTP://example.com/me.jpg", profilePhotoUrl("HTTP://example.com/me.jpg", "user-id", AVATARS_HOST))
    }

    @Test
    fun `an uploaded photo is the medium avatar in the user's folder`() {
        assertEquals(
            "$AVATARS_HOST/events/banner/en/avatars/user-id/medium.png?v=1788192266",
            profilePhotoUrl(".png.1788192266", "user-id", AVATARS_HOST)
        )
    }

    @Test
    fun `an uploaded photo's folder extension and version default as the backend's do`() {
        assertEquals(
            "$AVATARS_HOST/events/banner/en/avatars/folder/medium.jpg?v=7",
            profilePhotoUrl("folder.jpg.7", "user-id", AVATARS_HOST),
            "a file name names the folder"
        )
        assertEquals(
            "$AVATARS_HOST/events/banner/en/avatars/folder/medium.png?v=0",
            profilePhotoUrl("folder", "user-id", AVATARS_HOST),
            "no extension or version"
        )
        assertEquals(
            "$AVATARS_HOST/events/banner/en/avatars/user-id/medium.webp?v=0",
            profilePhotoUrl(".webp", "user-id", AVATARS_HOST),
            "no version"
        )
    }

    private companion object {
        const val AVATARS_HOST = "https://s3.amazonaws.com/qreflect-bucket"
    }
}

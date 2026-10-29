package com.quran.shared.auth.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** The published user is the ID token's identity with the fetched profile on top. */
class FetchedProfileTest {
    private val tokenUser = UserInfo(
        id = "user-id",
        firstName = "Old",
        lastName = "Name",
        name = "Old Name",
        email = "user@example.com"
    )

    @Test
    fun `the profile's name and photo replace the token's`() {
        val profile = profile(name = "New Name", firstName = "New", lastName = "Name", photoUrl = "https://photo")

        assertEquals(
            UserInfo(
                id = "user-id",
                firstName = "New",
                lastName = "Name",
                name = "New Name",
                email = "user@example.com",
                photoUrl = "https://photo"
            ),
            tokenUser.withProfile(profile)
        )
    }

    @Test
    fun `a field the profile no longer has is gone`() {
        val profile = profile(name = "Old", firstName = "Old", lastName = null)

        val user = tokenUser.withProfile(profile)

        assertEquals(null, user.lastName)
        assertEquals("Old", user.displayName)
    }

    @Test
    fun `a profile without an email keeps the token's`() {
        assertEquals("user@example.com", tokenUser.withProfile(profile(email = null)).email)
        assertEquals("new@example.com", tokenUser.withProfile(profile(email = "new@example.com")).email)
    }

    @Test
    fun `another account's profile changes nothing`() {
        assertEquals(tokenUser, tokenUser.withProfile(profile(id = "someone-else", name = "Someone Else")))
        assertEquals(tokenUser, tokenUser.withProfile(null))
    }

    private fun profile(
        id: String = "user-id",
        name: String? = "Old Name",
        firstName: String? = "Old",
        lastName: String? = "Name",
        email: String? = "user@example.com",
        photoUrl: String? = null
    ) = FetchedProfile(id, name, firstName, lastName, email, photoUrl, fetchedAt = 1_000L)
}

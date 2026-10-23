package com.quran.shared.auth.model

import com.quran.shared.auth.TEST_CLIENT_ID
import com.quran.shared.auth.TEST_ISSUER
import com.quran.shared.auth.TEST_SUBJECT
import com.quran.shared.auth.testIdToken
import com.quran.shared.auth.testJwt
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The account and profile read from an ID token, and the tokens that can't name one. */
class IdTokenUserTest {
    private val authConfig = AuthConfig(clientId = TEST_CLIENT_ID)

    /** JWT segments are unpadded base64url; a standard, padded decoder fails on both of these. */
    @Test
    fun `a real-shaped token whose payload uses the url-safe alphabet without padding names the account`() {
        val idToken = testIdToken(
            claims = mapOf(
                "name" to "Ayşe ~~~~~ ?????",
                "first_name" to "Ayşe",
                "last_name" to "Yılmaz",
                "email" to "ayse@example.com",
                "email_verified" to "true",
                "sid" to "session-id"
            )
        )
        val payload = idToken.split(".")[1]
        assertTrue('-' in payload && '_' in payload, "the payload uses both url-safe characters: $payload")
        assertNotEquals(0, payload.length % 4, "the payload is not padded to a multiple of 4")

        val user = assertIs<IdTokenUser.Valid>(parseIdTokenUser(idToken, authConfig)).userInfo

        assertEquals(
            UserInfo(
                id = TEST_SUBJECT,
                firstName = "Ayşe",
                lastName = "Yılmaz",
                name = "Ayşe ~~~~~ ?????",
                email = "ayse@example.com"
            ),
            user
        )
    }

    /**
     * The claim set prelive's Ory Hydra issues, with made-up values: a sign-in token and the one a refresh returns,
     * which keeps `sub`, `iss`, `aud`, `sid` and the profile claims and renews the rest.
     */
    @Test
    fun `the claim set prelive issues at sign-in and on refresh names the account`() {
        val prelive = AuthConfig(environment = AuthEnvironment.PRELIVE, clientId = TEST_CLIENT_ID)
        val subject = "3b9d7c1e-58f2-4a06-9e4b-d2a17c6f0b83"
        fun hydraIdToken(tokenId: String, atHash: String, issuedAt: Long) = testJwt(
            buildJsonObject {
                put("at_hash", atHash)
                put("aud", buildJsonArray { add(JsonPrimitive(TEST_CLIENT_ID)) })
                put("auth_time", 1_761_200_000L)
                put("email", "omar.haddad@example.com")
                put("email_verified", true)
                put("exp", issuedAt + 3600)
                put("first_name", "Omar")
                put("iat", issuedAt)
                put("iss", "https://prelive-oauth2.quran.foundation")
                put("jti", tokenId)
                put("last_name", "Haddad")
                put("name", "Omar Haddad")
                put("rat", 1_761_199_990L)
                put("sid", "8e0f4a2c-7d1b-4c39-a5e6-0b9f2d4c8a17")
                put("sub", subject)
            }
        )
        val signIn = hydraIdToken("5c2e9a10-3f7b-4d8e-b1a6-9e0c4d7f2b35", "Qm9xN2dYc0h2bVR3", 1_761_200_000L)
        val refreshed = hydraIdToken("a7d4f1c8-0e2b-4f59-8c3d-6b1e9a0f4c72", "Wk1hTDNyUGt2QjR4", 1_761_203_000L)
        val expected = UserInfo(
            id = subject,
            firstName = "Omar",
            lastName = "Haddad",
            name = "Omar Haddad",
            email = "omar.haddad@example.com"
        )

        listOf(signIn, refreshed).forEach { idToken ->
            assertEquals(expected, assertIs<IdTokenUser.Valid>(parseIdTokenUser(idToken, prelive)).userInfo)
        }
    }

    @Test
    fun `a payload whose length is not a multiple of 4 decodes`() {
        val lengths = (0 until 4).map { padding ->
            val idToken = testIdToken(claims = mapOf("name" to "A".repeat(padding)))
            val payload = idToken.split(".")[1]
            assertEquals(TEST_SUBJECT, assertIs<IdTokenUser.Valid>(parseIdTokenUser(idToken, authConfig)).userInfo.id)
            payload.length % 4
        }
        assertTrue(lengths.any { it != 0 }, "some payload needs padding: $lengths")
    }

    @Test
    fun `the OIDC standard name claims fill in when the custom ones are missing`() {
        val idToken = testIdToken(
            claims = mapOf(
                "given_name" to "Given",
                "family_name" to "Family",
                "picture" to "https://example.com/me.png"
            )
        )

        val user = assertIs<IdTokenUser.Valid>(parseIdTokenUser(idToken, authConfig)).userInfo

        assertEquals("Given", user.firstName)
        assertEquals("Family", user.lastName)
        assertEquals("https://example.com/me.png", user.photoUrl)
        assertEquals("Given Family", user.displayName)
    }

    @Test
    fun `the custom name claims win over the OIDC standard ones`() {
        val idToken = testIdToken(
            claims = mapOf(
                "first_name" to "First",
                "given_name" to "Given",
                "last_name" to "Last",
                "family_name" to "Family"
            )
        )

        val user = assertIs<IdTokenUser.Valid>(parseIdTokenUser(idToken, authConfig)).userInfo

        assertEquals("First", user.firstName)
        assertEquals("Last", user.lastName)
    }

    @Test
    fun `a single string audience naming the client is accepted`() {
        val idToken = testJwt(
            buildJsonObject {
                put("sub", TEST_SUBJECT)
                put("iss", TEST_ISSUER)
                put("aud", TEST_CLIENT_ID)
            }
        )

        assertEquals(TEST_SUBJECT, assertIs<IdTokenUser.Valid>(parseIdTokenUser(idToken, authConfig)).userInfo.id)
    }

    @Test
    fun `a malformed token is rejected`() {
        listOf("", "new-id-token", "not.base64!.signature", "a.b.c.d.e").forEach { idToken ->
            assertIs<IdTokenUser.Rejected>(parseIdTokenUser(idToken, authConfig), "'$idToken'")
        }
    }

    @Test
    fun `a token without a subject is rejected`() {
        val missing = testJwt(
            buildJsonObject {
                put("iss", TEST_ISSUER)
                put("aud", TEST_CLIENT_ID)
            }
        )
        val blank = testIdToken(subject = " ")

        assertIs<IdTokenUser.Rejected>(parseIdTokenUser(missing, authConfig))
        assertIs<IdTokenUser.Rejected>(parseIdTokenUser(blank, authConfig))
    }

    @Test
    fun `a token from another issuer is rejected`() {
        val trailingSlash = testIdToken(issuer = "$TEST_ISSUER/")
        val otherIssuer = testIdToken(issuer = "https://accounts.example.com")
        val missingIssuer = testJwt(
            buildJsonObject {
                put("sub", TEST_SUBJECT)
                put("aud", TEST_CLIENT_ID)
            }
        )

        listOf(trailingSlash, otherIssuer, missingIssuer).forEach { idToken ->
            assertIs<IdTokenUser.Rejected>(parseIdTokenUser(idToken, authConfig))
        }
    }

    @Test
    fun `a token for another client is rejected`() {
        val otherClient = testIdToken(audience = listOf("other-client"))
        val noAudience = testJwt(
            buildJsonObject {
                put("sub", TEST_SUBJECT)
                put("iss", TEST_ISSUER)
            }
        )

        val rejection = assertIs<IdTokenUser.Rejected>(parseIdTokenUser(otherClient, authConfig))
        assertTrue("other-client" in rejection.reason, rejection.reason)
        assertIs<IdTokenUser.Rejected>(parseIdTokenUser(noAudience, authConfig))
    }

    @Test
    fun `a rejection never contains the token`() {
        val idToken = testIdToken(issuer = "https://accounts.example.com")

        val rejection = assertIs<IdTokenUser.Rejected>(parseIdTokenUser(idToken, authConfig))

        assertTrue(idToken !in rejection.reason && idToken.split(".")[1] !in rejection.reason, rejection.reason)
    }

    @Test
    fun `a non-string profile claim is ignored`() {
        val idToken = testJwt(
            buildJsonObject {
                put("sub", TEST_SUBJECT)
                put("iss", TEST_ISSUER)
                put("aud", TEST_CLIENT_ID)
                put("name", JsonPrimitive(42))
            }
        )

        assertEquals(null, assertIs<IdTokenUser.Valid>(parseIdTokenUser(idToken, authConfig)).userInfo.name)
    }
}

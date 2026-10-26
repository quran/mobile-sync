package com.quran.shared.auth

import com.quran.shared.auth.model.AuthConfig
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val TEST_CLIENT_ID = "client-id"
internal const val TEST_SUBJECT = "6f1c2d3e-4a5b-4c6d-8e7f-90a1b2c3d4e5"
internal val TEST_ISSUER: String = AuthConfig(clientId = TEST_CLIENT_ID).baseUrl

/**
 * A well-formed, unsigned ID token like the ones Ory Hydra issues: an RS256 header and the claims, each base64url
 * encoded without padding, and a dummy signature. [tokenId] (the `jti`) tells tokens of the same account apart.
 */
internal fun testIdToken(
    tokenId: String = "id-token",
    subject: String = TEST_SUBJECT,
    issuer: String = TEST_ISSUER,
    audience: List<String> = listOf(TEST_CLIENT_ID),
    claims: Map<String, String> = emptyMap()
): String =
    testJwt(
        buildJsonObject {
            put("sub", subject)
            put("iss", issuer)
            put("aud", buildJsonArray { audience.forEach { add(JsonPrimitive(it)) } })
            put("jti", tokenId)
            put("iat", 1_760_000_000L)
            put("exp", 1_760_003_600L)
            claims.forEach { (name, value) -> put(name, value) }
        }
    )

/** Encodes [payload] as the middle segment of an unsigned JWT. */
internal fun testJwt(payload: JsonObject): String {
    val header = """{"alg":"RS256","kid":"public:test","typ":"JWT"}"""
    return listOf(header, payload.toString(), "dummy-signature")
        .joinToString(".") { base64Url.encode(it.encodeToByteArray()) }
}

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

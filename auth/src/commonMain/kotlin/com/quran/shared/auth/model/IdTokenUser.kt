package com.quran.shared.auth.model

import org.publicvalue.multiplatform.oidc.types.Jwt

/** The signed-in user read from an ID token, or why the token was rejected. */
internal sealed interface IdTokenUser {
    data class Valid(val userInfo: UserInfo) : IdTokenUser

    /** [reason] describes the rejection without the token itself, so it is safe to log. */
    data class Rejected(val reason: String) : IdTokenUser
}

/**
 * Reads the user from an ID token the token endpoint returned: `sub` is the account id, and the profile claims fill
 * in the name, email and picture.
 *
 * The signature is not checked: OIDC Core §3.1.3.7 allows skipping it for a token received directly from the token
 * endpoint over TLS. The token must still be for this client from this issuer, so `iss` must be the configured base
 * URL and `aud` must contain the client id.
 */
internal fun parseIdTokenUser(idToken: String, authConfig: AuthConfig): IdTokenUser {
    val claims = try {
        Jwt.parse(idToken).payload
    } catch (e: Exception) {
        return IdTokenUser.Rejected("the ID token is not a well-formed JWT (${e::class.simpleName}: ${e.message})")
    }
    val subject = claims.sub
    if (subject.isNullOrBlank()) {
        return IdTokenUser.Rejected("the ID token has no subject")
    }
    if (claims.iss != authConfig.baseUrl) {
        return IdTokenUser.Rejected("the ID token's issuer '${claims.iss}' is not '${authConfig.baseUrl}'")
    }
    if (claims.aud?.contains(authConfig.clientId) != true) {
        return IdTokenUser.Rejected("the ID token's audience ${claims.aud} does not include this client")
    }
    val profile = claims.additionalClaims
    fun claim(name: String): String? = (profile[name] as? String)?.takeIf { it.isNotBlank() }
    return IdTokenUser.Valid(
        UserInfo(
            id = subject,
            firstName = claim("first_name") ?: claim("given_name"),
            lastName = claim("last_name") ?: claim("family_name"),
            name = claim("name"),
            email = claim("email"),
            photoUrl = claim("picture")
        )
    )
}

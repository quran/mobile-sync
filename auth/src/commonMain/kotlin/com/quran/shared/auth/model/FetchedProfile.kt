package com.quran.shared.auth.model

import kotlin.native.HiddenFromObjC
import kotlinx.serialization.Serializable

/**
 * The signed-in user's profile as the account API returned it, stored apart from the ID token's claims.
 *
 * The ID token repeats the claims captured when the user consented, on every refresh, so a name or photo changed on
 * Quran.com later reaches the app only through this. [id] is the account it belongs to, [photoUrl] a full URL (or null
 * for no photo), and [fetchedAt] when it was fetched, in epoch milliseconds.
 */
@HiddenFromObjC
@Serializable
data class FetchedProfile(
    val id: String,
    val name: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val fetchedAt: Long
)

/**
 * The session a profile fetch is for, captured before its request:
 * [com.quran.shared.auth.service.AuthService.storeFetchedProfile] stores the response only while this is still the
 * current session (no logout or sign-in since) and only for [accountId].
 *
 * [generation] is the repository's session generation, which a logout or sign-in moves on. [fetchedAt] and
 * [attemptedAt] are this session's last successful fetch and last attempt, in epoch milliseconds, or null when there
 * was none.
 */
@HiddenFromObjC
data class ProfileFetchSession(
    val generation: Long,
    val accountId: String,
    val fetchedAt: Long?,
    val attemptedAt: Long?
)

/** What became of a fetched profile. */
@HiddenFromObjC
enum class FetchedProfileWrite {
    /** Stored for the session; the published user now shows it. */
    STORED,

    /** Dropped: it names another account than the session's. */
    OTHER_ACCOUNT,

    /** Dropped: the session ended, or a logout or sign-in replaced it, during the fetch; or there is none. */
    SESSION_CHANGED
}

/**
 * The published user: the ID token's identity with [profile]'s fields on top, when [profile] is this account's.
 *
 * The profile's name, first and last name, and photo replace the token's, also when they are empty (a last name
 * removed on the web is gone). Its email replaces the token's only when it has one: email can't be changed on the web,
 * and the token's is the one the user signed in with.
 */
internal fun UserInfo.withProfile(profile: FetchedProfile?): UserInfo {
    if (profile == null || profile.id != id) return this
    return copy(
        name = profile.name,
        firstName = profile.firstName,
        lastName = profile.lastName,
        email = profile.email ?: email,
        photoUrl = profile.photoUrl
    )
}

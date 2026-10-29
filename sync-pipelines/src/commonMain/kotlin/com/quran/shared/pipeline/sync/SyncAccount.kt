package com.quran.shared.pipeline.sync

import com.quran.shared.auth.model.FetchedProfile
import com.quran.shared.auth.model.FetchedProfileWrite
import com.quran.shared.auth.model.ProfileFetchSession
import com.quran.shared.auth.service.AuthService

/** The signed-in session, as sync needs it. */
internal interface SyncAccount {
    /** Headers for a sync request; empty when no one is signed in. */
    suspend fun authHeaders(): Map<String, String>

    /** The signed-in account's stable id (the token's `sub`). */
    suspend fun accountId(): String?

    /** Refreshes the token the server rejected; false when the session ended instead. */
    suspend fun forceTokenRefresh(): Boolean

    /** The session a profile fetch is for, with its last fetch and attempt; null when no one is signed in. */
    suspend fun profileFetchSession(): ProfileFetchSession? = null

    /** Records [session]'s attempt to fetch its profile, unless the session changed since. */
    suspend fun recordProfileFetchAttempt(session: ProfileFetchSession, attemptedAt: Long) = Unit

    /** Stores [profile] for [session], unless the session changed since or [profile] is another account's. */
    suspend fun storeFetchedProfile(session: ProfileFetchSession, profile: FetchedProfile): FetchedProfileWrite =
        FetchedProfileWrite.SESSION_CHANGED
}

/** The signed-in session of [authService], the app's: what [com.quran.shared.pipeline.QuranDataService] syncs for. */
internal class AuthServiceSyncAccount(private val authService: AuthService) : SyncAccount {
    override suspend fun authHeaders(): Map<String, String> = authService.getAuthHeaders()

    override suspend fun accountId(): String? = authService.currentAccountId()

    override suspend fun forceTokenRefresh(): Boolean = authService.forceRefreshAccessToken()

    override suspend fun profileFetchSession(): ProfileFetchSession? = authService.profileFetchSession()

    override suspend fun recordProfileFetchAttempt(session: ProfileFetchSession, attemptedAt: Long) {
        authService.recordProfileFetchAttempt(session, attemptedAt)
    }

    override suspend fun storeFetchedProfile(session: ProfileFetchSession, profile: FetchedProfile): FetchedProfileWrite =
        authService.storeFetchedProfile(session, profile)
}

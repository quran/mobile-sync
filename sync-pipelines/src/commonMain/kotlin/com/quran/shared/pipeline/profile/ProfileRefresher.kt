package com.quran.shared.pipeline.profile

import co.touchlab.kermit.Logger
import com.quran.shared.auth.model.FetchedProfile
import com.quran.shared.auth.model.FetchedProfileWrite
import com.quran.shared.auth.model.ProfileFetchSession
import com.quran.shared.pipeline.sync.SyncAccount
import com.quran.shared.pipeline.sync.SyncLifecycleGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Keeps the signed-in user's name and photo current. The ID token repeats the claims captured when the user consented,
 * so an edit on Quran.com reaches the app only through the account's profile ([ProfileApi]). A fetched profile is
 * stored with the session, which republishes `authState` when it changed the user.
 *
 * - [refresh] fetches now: the app calls it when the user may have just edited their profile.
 * - [refreshIfDue] fetches in the background, from each sync run, when this session's profile is [FETCH_INTERVAL] old
 *   or it has none (a new sign-in clears it, so a sign-in's sync fetches it), and it last tried more than
 *   [RETRY_INTERVAL] ago. So it costs about one request a day, and an outage one an hour. It runs no timer of its own.
 *
 * One fetch runs at a time per session: a call during a fetch for the same session joins it, and one after a logout or
 * sign-in starts its own, since the previous session's would be dropped. A 401 forces one token refresh and tries once more. A
 * failure (the network, an error status, a response that isn't a profile) keeps the stored profile and the session.
 * A profile for another account, or one whose session a logout or sign-in replaced during the request, is dropped.
 * Nothing here ends the session, though the forced refresh does when the refresh token is refused, as for sync.
 */
internal class ProfileRefresher(
    private val api: ProfileApi,
    private val account: SyncAccount,
    private val gate: SyncLifecycleGate,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long
) : AutoCloseable {
    private val logger = Logger.withTag("ProfileRefresher")
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val lock = Mutex()
    private var running: RunningFetch? = null

    /** A fetch in progress and the session it is for. */
    private class RunningFetch(val session: ProfileFetchSession, val fetch: Deferred<Unit>)

    /**
     * Fetches the profile now, or joins the current session's fetch in progress. Returns without a request when no one
     * is signed in or a logout or reset is running.
     *
     * @throws Exception the fetch's failure: [IOException] for the network, [ProfileHttpException] for an error
     * status, [UnexpectedProfileResponseException] for a response that isn't a profile.
     */
    suspend fun refresh() {
        val session = currentSession() ?: return
        fetchOrJoin(session).await()
    }

    /** Starts a fetch in the background when it is due (see [ProfileRefresher]). Failures are only logged. */
    fun refreshIfDue() {
        scope.launch {
            try {
                val session = currentSession() ?: return@launch
                if (isDue(session)) fetchOrJoin(session).await()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // fetch() logged it.
            }
        }
    }

    /** Stops for good: a fetch in progress is cancelled, and later calls do nothing. */
    override fun close() {
        scope.cancel()
    }

    /** The session to fetch for; null when no one is signed in or a logout or reset is running. */
    private suspend fun currentSession(): ProfileFetchSession? {
        if (!gate.canStartSync()) {
            logger.d { "Not fetching the profile: a logout or reset is running" }
            return null
        }
        return account.profileFetchSession() ?: run {
            logger.d { "Not fetching the profile: no one is signed in" }
            null
        }
    }

    private fun isDue(session: ProfileFetchSession): Boolean {
        val now = now()
        return session.fetchedAt.isOlderThan(FETCH_INTERVAL, now) && session.attemptedAt.isOlderThan(RETRY_INTERVAL, now)
    }

    /** Null (never) is older than any interval; so is a time in the future, as when the clock was set back since. */
    private fun Long?.isOlderThan(interval: Duration, now: Long): Boolean =
        this == null || now - this !in 0 until interval.inWholeMilliseconds

    /** Joins the fetch in progress when it is for [session]'s session, or starts one for it. */
    private suspend fun fetchOrJoin(session: ProfileFetchSession): Deferred<Unit> = lock.withLock {
        running?.takeIf { it.fetch.isActive && it.session.isSameSession(session) }?.fetch
            ?: scope.async { fetch(session) }.also { running = RunningFetch(session, it) }
    }

    private fun ProfileFetchSession.isSameSession(other: ProfileFetchSession): Boolean =
        generation == other.generation && accountId == other.accountId

    private suspend fun fetch(session: ProfileFetchSession) {
        account.recordProfileFetchAttempt(session, now())
        val profile = try {
            fetchWithOneRefresh()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (failure.isNetworkFailure()) {
                logger.i { "Could not fetch the profile: ${failure::class.simpleName}" }
            } else {
                logger.w(failure) { "Could not fetch the profile" }
            }
            throw failure
        } ?: run {
            logger.i { "Not storing the profile: the session ended during the fetch" }
            return
        }
        if (account.storeFetchedProfile(session, profile) == FetchedProfileWrite.STORED) {
            logger.d { "Stored the fetched profile" }
        }
    }

    /** The profile, or null when the session ended (no headers, or a refused forced refresh). */
    private suspend fun fetchWithOneRefresh(): FetchedProfile? {
        val headers = account.authHeaders().ifEmpty { return null }
        try {
            return api.fetch(headers, now)
        } catch (rejected: ProfileHttpException) {
            if (rejected.status != UNAUTHORIZED) throw rejected
        }
        logger.i { "The server rejected the token for the profile; refreshing it" }
        if (!account.forceTokenRefresh()) return null
        return api.fetch(account.authHeaders().ifEmpty { return null }, now)
    }

    private fun Throwable.isNetworkFailure(): Boolean = generateSequence(this) { it.cause }.any { it is IOException }

    companion object {
        /** How old a profile may get before a sync run fetches it again. */
        val FETCH_INTERVAL: Duration = 24.hours

        /** How long after an attempt, failed or not, a sync run waits before trying again. */
        val RETRY_INTERVAL: Duration = 1.hours

        private const val UNAUTHORIZED = 401
    }
}

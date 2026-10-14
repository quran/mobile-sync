package com.quran.shared.pipeline.sync

import co.touchlab.kermit.Logger
import com.quran.shared.syncengine.SyncError
import com.quran.shared.syncengine.SyncErrorKind
import com.quran.shared.syncengine.SyncLifecycleGate
import com.quran.shared.syncengine.SyncOperationInvalidatedException
import com.quran.shared.syncengine.SyncRunObserver
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.quran.shared.syncengine.network.HttpClientFactory
import com.quran.shared.syncengine.scheduling.SchedulerTimings
import com.quran.shared.syncv2.SyncApi
import com.quran.shared.syncv2.SyncEngineV2
import com.quran.shared.syncv2.SyncHttpException
import com.quran.shared.syncv2.SyncResult
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.syncTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import io.ktor.client.HttpClient
import kotlinx.io.IOException
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** The signed-in session, as sync needs it. */
internal interface SyncAccount {
    /** Headers for a sync request; empty when no one is signed in. */
    suspend fun authHeaders(): Map<String, String>

    /** The signed-in account's stable id (the token's `sub`). */
    suspend fun accountId(): String?

    /** Refreshes the token the server rejected; false when the session ended instead. */
    suspend fun forceTokenRefresh(): Boolean
}

/** The session ended during a run: its requests have no token. */
internal class SignedOutException : IllegalStateException("No session")

/**
 * Runs [SyncEngineV2] when [SyncScheduler] says, for the signed-in account, and reports each run to [observer]:
 * [SyncRunObserver.syncStarted] once signed in (again for each retry), then exactly one of succeeded, skipped (no
 * session, a logout or reset) or failed (when it won't be retried).
 *
 * - A 401 forces one token refresh and runs again; a refused refresh ends the session (S25).
 * - A failure that asks for a push backoff (a 403 that may be the token's scope) stops pushing for that long; the
 *   periodic and other automatic runs still pull. A "sync now" pushes anyway.
 */
internal class SyncClient(
    private val engine: SyncEngineV2,
    private val account: SyncAccount,
    private val gate: SyncLifecycleGate,
    private val observer: SyncRunObserver,
    timings: SchedulerTimings,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Closed with this client. */
    private val httpClient: HttpClient? = null
) : SynchronizationClient, AutoCloseable {
    private val logger = Logger.withTag("SyncClient")
    private val scheduler = SyncScheduler(timings, dispatcher, now, ::runAttempt)

    @Volatile
    private var pushBackoffUntil = 0L

    override fun localDataUpdated() = trigger(SyncTrigger.LOCAL_CHANGE)

    override fun applicationStarted() = trigger(SyncTrigger.APP_START)

    override fun triggerSyncImmediately() = trigger(SyncTrigger.IMMEDIATE)

    override fun cancelSyncing() = scheduler.cancel()

    override suspend fun cancelSyncingAndJoin() = scheduler.cancelAndJoin()

    /** Stops the scheduler for good and closes the HTTP client: the service that owns this client is gone. */
    override fun close() {
        scheduler.close()
        httpClient?.close()
    }

    private fun trigger(trigger: SyncTrigger) {
        if (gate.canStartSync()) scheduler.trigger(trigger) else logger.d { "Sync lifecycle unavailable, skipping $trigger" }
    }

    private suspend fun runAttempt(attempt: SyncAttempt, willRetry: (AttemptOutcome.Failed) -> Boolean): AttemptOutcome {
        val epoch = startEpoch() ?: return skipped("the sync lifecycle is unavailable")
        // A retry continues a failed run, so it reports syncing even if fetching headers fails again.
        if (attempt.number > 0) observer.syncStarted()
        val push = attempt.trigger == SyncTrigger.IMMEDIATE || now() >= pushBackoffUntil
        val result = try {
            if (account.authHeaders().isEmpty()) return skipped("no one is signed in")
            val accountId = account.accountId() ?: return skipped("the signed-in account is unknown")
            if (attempt.number == 0) observer.syncStarted()
            sync(accountId, push)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            SyncResult.Failed(error.toString(), retryable = true, cause = error)
        }
        if (!epochIsCurrent(epoch)) return skipped("a logout or reset began during the run")
        return when (result) {
            is SyncResult.Success -> {
                if (push) {
                    pushBackoffUntil = 0L
                    observer.syncSucceeded()
                } else {
                    // A pull during a push backoff doesn't clear the failure that started it: syncStatus keeps
                    // Failed until a run that pushes succeeds.
                    observer.syncSkipped()
                }
                AttemptOutcome.Done
            }
            is SyncResult.Failed -> failed(result, willRetry)
        }
    }

    /**
     * One engine run; a 401 forces a token refresh and runs once more. A 401 again, with a token the authorization
     * server just issued, isn't retried in this run: the next run (periodic, or a user's) may refresh once more. So a
     * server that keeps rejecting tokens gets at most one refresh per run, never a storm.
     */
    private suspend fun sync(accountId: String, push: Boolean): SyncResult {
        val result = engine.sync(accountId, push)
        if ((result as? SyncResult.Failed)?.httpStatus != UNAUTHORIZED) return result
        logger.i { "The server rejected the token; refreshing it" }
        if (!account.forceTokenRefresh()) {
            return SyncResult.Failed("the session ended", retryable = false, cause = SignedOutException())
        }
        val again = engine.sync(accountId, push)
        if ((again as? SyncResult.Failed)?.httpStatus != UNAUTHORIZED) return again
        logger.w { "The server rejected a freshly refreshed token; not retrying in this run" }
        return again.copy(retryable = false)
    }

    private fun failed(result: SyncResult.Failed, willRetry: (AttemptOutcome.Failed) -> Boolean): AttemptOutcome {
        when (result.cause) {
            is SignedOutException -> return skipped("the session ended")
            is SyncOperationInvalidatedException -> return skipped("a logout or reset began during the run")
        }
        result.retryAfterMillis?.let { pushBackoffUntil = now() + it }
        val outcome = AttemptOutcome.Failed(result.retryable, result.retryAfterMillis)
        if (!willRetry(outcome)) {
            logger.w { "Sync failed: ${result.reason}" }
            observer.syncFailed(result.toSyncError())
        }
        return outcome
    }

    private fun skipped(why: String): AttemptOutcome {
        logger.i { "Sync skipped: $why" }
        observer.syncSkipped()
        return AttemptOutcome.Done
    }

    @Volatile
    private var runEpoch: Long? = null

    private suspend fun startEpoch(): Long? {
        if (!gate.canStartSync()) return null
        return try {
            gate.captureSyncEpoch().also { runEpoch = it }
        } catch (invalidated: SyncOperationInvalidatedException) {
            null
        }
    }

    private suspend fun epochIsCurrent(epoch: Long): Boolean = try {
        gate.checkSyncEpoch(epoch)
        true
    } catch (invalidated: SyncOperationInvalidatedException) {
        false
    }

    /**
     * Headers for each request of a run: none once a logout or reset began (admitted under the gate's lock, so a
     * request can't slip out after a reset started), or once the session ended.
     */
    private suspend fun requestHeaders(): Map<String, String> {
        runEpoch?.let { gate.admitSyncPost(it) }
        return account.authHeaders().ifEmpty { throw SignedOutException() }
    }

    companion object {
        private const val UNAUTHORIZED = 401

        /** Holds are reviewed again when this changes (see [SyncEngineV2]). */
        private const val ENGINE_VERSION = "mobile-sync-2"

        fun create(
            database: SyncV2Database,
            environment: SynchronizationEnvironment,
            account: SyncAccount,
            gate: SyncLifecycleGate,
            observer: SyncRunObserver,
            timings: SchedulerTimings = DefaultSyncTimings
        ): SyncClient {
            lateinit var client: SyncClient
            val httpClient = HttpClientFactory.createHttpClient()
            val api = SyncApi(httpClient, environment.endPointURL) { client.requestHeaders() }
            val engine = SyncEngineV2(database, api, syncTypes(database), ENGINE_VERSION)
            client = SyncClient(engine, account, gate, observer, timings, httpClient = httpClient)
            return client
        }
    }
}

private val SyncResult.Failed.httpStatus: Int? get() = (cause as? SyncHttpException)?.status

/**
 * A failed run as the app sees it: a stable category, the HTTP status and the server's error code. Never a response
 * body or user data.
 */
internal fun SyncResult.Failed.toSyncError(): SyncError {
    val cause = cause
    // A network error, also when the token refresh before a request failed on one (the OIDC library wraps it).
    if (cause != null && generateSequence<Throwable>(cause) { it.cause }.any { it is IOException }) {
        return SyncError(SyncErrorKind.NETWORK, null, null, "Could not reach the sync server")
    }
    return httpOrUnknown(cause)
}

private fun SyncResult.Failed.httpOrUnknown(cause: Throwable?): SyncError = when (cause) {
    is SyncHttpException -> {
        val code = cause.status
        val (kind, description) = when {
            code == 401 || code == 403 -> SyncErrorKind.AUTHENTICATION to "The sync server did not accept the session"
            code >= 500 || code == 408 || code == 429 -> SyncErrorKind.SERVER to "The sync server is unavailable"
            code in 400..499 -> SyncErrorKind.REJECTED to "The sync server rejected the request"
            else -> SyncErrorKind.UNKNOWN to "The sync server returned an unexpected status"
        }
        SyncError(kind, code, cause.code, "$description (HTTP $code)")
    }
    null -> SyncError(SyncErrorKind.UNKNOWN, null, null, "Sync failed unexpectedly ($reason)")
    else -> SyncError(SyncErrorKind.UNKNOWN, null, null, "Sync failed unexpectedly (${cause::class.simpleName})")
}

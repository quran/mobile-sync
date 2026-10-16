package com.quran.shared.pipeline.sync

import co.touchlab.kermit.Logger
import com.quran.shared.logging.installHttpLogging
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.pipeline.SyncError
import com.quran.shared.pipeline.SyncErrorKind
import com.quran.shared.pipeline.SyncRun
import com.quran.shared.pipeline.SyncRunResult
import com.quran.shared.pipeline.SyncRunTrigger
import com.quran.shared.pipeline.SynchronizationEnvironment
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
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.io.IOException
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

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
 * [SyncRunObserver.syncStarted] once signed in (again for each retry), then one of skipped (no session, a
 * logout or reset) or ended ([SyncRun]: succeeded, pulled only, failed when it won't be retried, or cancelled).
 * A run's record sums its attempts: their count, and what each pushed and pulled. A run the session's end stops
 * after it sent a request is both: recorded cancelled, then skipped (see [SyncRunObserver]).
 *
 * - A 401 forces one token refresh and runs again; a refused refresh ends the session.
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

    override fun signedIn() = trigger(SyncTrigger.SIGN_IN)

    override fun sessionRestored() = trigger(SyncTrigger.SESSION_RESTORED)

    override fun cancelSyncing() = scheduler.cancel()

    /** Also forgets a run waiting for a retry: cancelled for a logout or reset, it isn't recorded. */
    override suspend fun cancelSyncingAndJoin() {
        scheduler.cancelAndJoin()
        run = null
    }

    /** Stops the scheduler for good and closes the HTTP client: the service that owns this client is gone. */
    override fun close() {
        scheduler.close()
        httpClient?.close()
    }

    private fun trigger(trigger: SyncTrigger) {
        if (gate.canStartSync()) scheduler.trigger(trigger) else logger.d { "Sync lifecycle unavailable, skipping $trigger" }
    }

    /**
     * Runs one attempt. A run cancelled while the sync lifecycle is open (the service is cleared) is reported
     * [SyncRunResult.CANCELLED]; one a logout or reset cancels reports nothing, as its data is wiped.
     */
    private suspend fun runAttempt(attempt: SyncAttempt, willRetry: (AttemptOutcome.Failed) -> Boolean): AttemptOutcome = try {
        attemptOnce(attempt, willRetry)
    } catch (cancellation: CancellationException) {
        if (gate.canStartSync()) end(SyncRunResult.CANCELLED, heldCount()) else run = null
        throw cancellation
    }

    private suspend fun attemptOnce(attempt: SyncAttempt, willRetry: (AttemptOutcome.Failed) -> Boolean): AttemptOutcome {
        val epoch = startEpoch() ?: return skipped("the sync lifecycle is unavailable")
        val tally = startRun(attempt, epoch)
        // A retry continues a failed run, so it reports syncing even if fetching headers fails again.
        if (attempt.number > 0) observer.syncStarted()
        val push = attempt.trigger.isImmediate || now() >= pushBackoffUntil
        val result = try {
            if (account.authHeaders().isEmpty()) return skipped("no one is signed in")
            val accountId = account.accountId() ?: return skipped("the signed-in account is unknown")
            if (attempt.number == 0) observer.syncStarted()
            sync(accountId, push, tally)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            SyncResult.Failed(error.toString(), retryable = true, cause = error)
        }
        if (!epochIsCurrent(epoch)) return skipped("a logout or reset began during the run")
        return when (result) {
            is SyncResult.Success -> {
                if (push) pushBackoffUntil = 0L
                // A pull during a push backoff doesn't clear the failure that started it: syncStatus keeps
                // Failed until a run that pushes succeeds.
                end(if (push) SyncRunResult.SUCCEEDED else SyncRunResult.PULLED_ONLY, result.held.size)
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
    private suspend fun sync(accountId: String, push: Boolean, tally: RunTally): SyncResult {
        val result = tally.add(engine.sync(accountId, push))
        if ((result as? SyncResult.Failed)?.httpStatus != UNAUTHORIZED) return result
        logger.i { "The server rejected the token; refreshing it" }
        if (!account.forceTokenRefresh()) {
            return SyncResult.Failed("the session ended", retryable = false, cause = SignedOutException())
        }
        val again = tally.add(engine.sync(accountId, push))
        if ((again as? SyncResult.Failed)?.httpStatus != UNAUTHORIZED) return again
        logger.w { "The server rejected a freshly refreshed token; not retrying in this run" }
        return again.copy(retryable = false)
    }

    private suspend fun failed(result: SyncResult.Failed, willRetry: (AttemptOutcome.Failed) -> Boolean): AttemptOutcome {
        when (result.cause) {
            is SignedOutException -> return skipped("the session ended")
            is SyncOperationInvalidatedException -> return skipped("a logout or reset began during the run")
        }
        result.retryAfterMillis?.let { pushBackoffUntil = now() + it }
        val outcome = AttemptOutcome.Failed(result.retryable, result.retryAfterMillis)
        if (!willRetry(outcome)) {
            logger.w { "Sync failed: ${result.reason}" }
            end(SyncRunResult.FAILED, heldCount(), result.toSyncError())
        }
        return outcome
    }

    /**
     * Ends the attempt without a result. A run that already sent a request (in this attempt, or in one before the
     * retry that found no session) is recorded [SyncRunResult.CANCELLED] with what it did, unless a logout or reset
     * began since; any other is forgotten. Either way it is skipped, so `syncStatus` goes back to what it was.
     */
    private suspend fun skipped(why: String): AttemptOutcome {
        val open = run
        if (open != null && open.contactedServer && gate.canStartSync() && epochIsCurrent(open.epoch)) {
            logger.i { "Sync stopped: $why" }
            end(SyncRunResult.CANCELLED, heldCount())
        } else {
            logger.i { "Sync skipped: $why" }
            run = null
        }
        observer.syncSkipped()
        return AttemptOutcome.Done
    }

    /**
     * The run in progress, from its first attempt until it ends. Attempts run one at a time, and the scheduler's
     * cancel-and-join clears it only once none runs.
     */
    @Volatile
    private var run: RunTally? = null

    /**
     * The run [attempt] belongs to. A first attempt starts a new run; a run still open then was waiting for a retry
     * that an immediate trigger replaced, and is reported cancelled (unless a logout or reset moved the epoch since).
     */
    private fun startRun(attempt: SyncAttempt, epoch: Long): RunTally {
        val open = run
        val tally = if (attempt.number > 0 && open != null) open else {
            if (open != null && open.epoch == epoch) end(SyncRunResult.CANCELLED, heldCount())
            RunTally(attempt.trigger, now(), epoch)
        }
        tally.attempts += 1
        run = tally
        return tally
    }

    /** Ends the open run as [result] and reports it. */
    private fun end(result: SyncRunResult, held: Int, error: SyncError? = null) {
        val tally = run ?: return
        run = null
        observer.syncEnded(tally.toRun(result, now(), held, error))
    }

    /** The items that don't sync now, for a run's record; 0 when they can't be read. */
    private fun heldCount(): Int = try {
        engine.held().size
    } catch (e: Exception) {
        logger.w(e) { "Could not count held items" }
        0
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
     * request can't slip out after a reset started), or once the session ended. Headers given mark the run as one
     * that contacted the server.
     */
    private suspend fun requestHeaders(): Map<String, String> {
        runEpoch?.let { gate.admitSyncPost(it) }
        return account.authHeaders().ifEmpty { throw SignedOutException() }.also { run?.contactedServer = true }
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
            // The platform's engine (OkHttp on Android and the JVM, Darwin on iOS) comes from this module's dependencies.
            val httpClient = HttpClient {
                installHttpLogging(tag = "SyncHttp")
                defaultRequest { header(HttpHeaders.Accept, ContentType.Application.Json) }
            }
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

/**
 * A run's progress so far: its trigger, start, the gate epoch it started under, and its attempts' sums.
 * [contactedServer]: an attempt sent a request, so the run may have changed something here or on the server.
 */
private class RunTally(val trigger: SyncTrigger, val startedAt: Long, val epoch: Long) {
    var attempts = 0
    var pushed = 0
    var pulled = 0
    var contactedServer = false

    fun add(result: SyncResult): SyncResult = result.also {
        pushed += it.pushed
        pulled += it.pulled
    }

    fun toRun(result: SyncRunResult, endedAt: Long, held: Int, error: SyncError?) = SyncRun(
        startedAt = Instant.fromEpochMilliseconds(startedAt).toPlatform(),
        endedAt = Instant.fromEpochMilliseconds(endedAt).toPlatform(),
        trigger = trigger.toRunTrigger(),
        result = result,
        attempts = attempts,
        pushed = pushed,
        pulled = pulled,
        held = held,
        error = error
    )
}

internal fun SyncTrigger.toRunTrigger(): SyncRunTrigger = when (this) {
    SyncTrigger.APP_START, SyncTrigger.SESSION_RESTORED -> SyncRunTrigger.APP_START
    SyncTrigger.LOCAL_CHANGE -> SyncRunTrigger.LOCAL_CHANGE
    SyncTrigger.PERIODIC -> SyncRunTrigger.PERIODIC
    SyncTrigger.IMMEDIATE -> SyncRunTrigger.MANUAL
    SyncTrigger.SIGN_IN -> SyncRunTrigger.SIGN_IN
}

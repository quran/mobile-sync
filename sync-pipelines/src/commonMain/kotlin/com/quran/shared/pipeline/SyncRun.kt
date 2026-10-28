package com.quran.shared.pipeline

import com.quran.shared.persistence.util.PlatformDateTime

/**
 * One sync run, as [QuranDataService.syncHistory] lists it. A run is the first attempt and its retries, so a run
 * that was retried is still one entry.
 *
 * It never carries user data, server messages, or response bodies: only times, counts, and the [SyncError].
 *
 * @param startedAt when the run's first attempt began contacting the sync server.
 * @param endedAt when the run ended.
 * @param trigger what started the run.
 * @param result how the run ended.
 * @param attempts the attempts the run made: the first one plus each retry.
 * @param pushed the local changes the server accepted during the run.
 * @param pulled the local items the run created, changed, or deleted to take the server's changes.
 * @param held the items that were not syncing when the run ended, counted like [QuranDataService.heldItems]: held
 *   items and the items waiting on them.
 * @param error why the run failed: non-null exactly when [result] is [SyncRunResult.FAILED], and then equal to the
 *   error [SyncStatus.Failed] reports for it.
 */
data class SyncRun(
    val startedAt: PlatformDateTime,
    val endedAt: PlatformDateTime,
    val trigger: SyncRunTrigger,
    val result: SyncRunResult,
    val attempts: Int,
    val pushed: Int,
    val pulled: Int,
    val held: Int,
    val error: SyncError?
)

/** What started a sync run. */
enum class SyncRunTrigger {
    /**
     * The app started: the first sync after launch. It runs as soon as the saved session is restored, or after a
     * short delay.
     */
    APP_START,

    /** Local data changed: `triggerSync(immediately = false)`, or an edit through [QuranDataService]. */
    LOCAL_CHANGE,

    /** The periodic sync came due. */
    PERIODIC,

    /** The app asked for a sync now: `triggerSync(immediately = true)`, such as a user's "Sync now". */
    MANUAL,

    /** A user signed in. A saved session restored at launch is [APP_START], and a token refresh starts no run. */
    SIGN_IN
}

/** How a sync run ended. */
enum class SyncRunResult {
    /** It pulled the server's changes and pushed the local ones. */
    SUCCEEDED,

    /**
     * It pulled the server's changes but pushed nothing, because pushes are paused after a failure that may be the
     * session's scope. [QuranDataService.syncStatus] keeps showing that failure until a run that pushes succeeds.
     */
    PULLED_ONLY,

    /** It failed and has no retries left. [SyncRun.error] says why. */
    FAILED,

    /**
     * It was stopped before it ended: the service was cleared while an attempt ran, a "sync now" or a sign-in
     * replaced the retry it was waiting for, or the session ended after the run reached the server (a refused token
     * refresh, or a retry that found no one signed in). Its counts are what it did until then, and [SyncRun.attempts]
     * counts the attempt that was stopped. A run the session's end stopped leaves [QuranDataService.syncStatus] as it
     * was before the run.
     */
    CANCELLED
}

package com.quran.shared.pipeline

import com.quran.shared.persistence.util.PlatformDateTime

/**
 * Where the sync [QuranDataService.syncNow] started stopped being worth waiting for, or why none ran: what a
 * pull-to-refresh or a "Sync now" spinner waits for. By the time it is returned, [QuranDataService.syncStatus] and
 * [QuranDataService.syncHistory] show it too.
 */
sealed class SyncNowResult {
    /**
     * The run ended as [run] says: it succeeded, failed with no retries left ([SyncRun.error] says why), or was
     * cancelled. It never only pulls: an immediate run pushes even while pushes back off. [run] is the entry
     * [QuranDataService.syncHistory] records for it.
     */
    data class Ended(val run: SyncRun) : SyncNowResult()

    /**
     * The run's attempt failed at [failedAt] because of [error], and the run tries again on its own at
     * [nextAttemptAt], as [SyncStatus.Retrying] shows. Its end is reported only by [QuranDataService.syncStatus] and
     * [QuranDataService.syncHistory], so a caller isn't held through the backoff (offline, say).
     *
     * Like [SyncStatus.Retrying], it can tell of a retry that never comes: when a "sync now" arrived while the failed
     * attempt ran, that sync runs right after it instead, and the waiting run ends cancelled.
     */
    data class Retrying(
        val failedAt: PlatformDateTime,
        val error: SyncError,
        val nextAttemptAt: PlatformDateTime
    ) : SyncNowResult()

    /**
     * Nothing synced: no one is signed in, the signed-in account is unknown, or the session ended before the run
     * reached the server. Nothing was recorded. A session that ends after the run reached the server ends it
     * [SyncRunResult.CANCELLED] instead.
     */
    data object SignedOut : SyncNowResult()

    /**
     * Sync can't run now: a logout or reset is running or began during the run (which wipes what it did), or the
     * service was cleared before the run started, including while it finished an earlier logout at launch. Nothing
     * was recorded.
     */
    data object Unavailable : SyncNowResult()
}

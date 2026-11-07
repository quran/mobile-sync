package com.quran.shared.pipeline

import com.quran.shared.persistence.util.PlatformDateTime

/** How the sync [QuranDataService.syncNow] waited for ended, or why none ran. */
sealed class SyncNowResult {
    /** The run ended: succeeded, failed with no retries left, or cancelled. [run] is its `syncHistory` entry. */
    data class Ended(val run: SyncRun) : SyncNowResult()

    /**
     * The attempt failed and the run retries on its own at [nextAttemptAt]. The retry may never come: a "sync now"
     * during the failed attempt runs in its place.
     */
    data class Retrying(
        val failedAt: PlatformDateTime,
        val error: SyncError,
        val nextAttemptAt: PlatformDateTime
    ) : SyncNowResult()

    /** No one is signed in, or the account is unknown, or the session ended before the run reached the server. */
    data object SignedOut : SyncNowResult()

    /** A logout or reset is running or began during the run, or the service was cleared before the run started. */
    data object Unavailable : SyncNowResult()
}

package com.quran.shared.pipeline

import com.quran.shared.persistence.repository.PersistenceResetRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** [SyncHistory]: one row per run in `sync_run`, newest first, pruned to [SyncHistory.LIMIT], wiped with sync state. */
class SyncHistoryTest {
    private val database = inMemoryMobileSyncDatabase().synced
    private val history = SyncHistory(database)

    private fun run(n: Int, result: SyncRunResult = SyncRunResult.SUCCEEDED, error: SyncError? = null) = SyncRun(
        startedAt = Instant.fromEpochMilliseconds(1_760_000_000_000L + n * 1_000L).toPlatform(),
        endedAt = Instant.fromEpochMilliseconds(1_760_000_000_500L + n * 1_000L).toPlatform(),
        trigger = SyncRunTrigger.entries[n % SyncRunTrigger.entries.size],
        result = result,
        attempts = n + 1,
        pushed = n,
        pulled = 2 * n,
        held = n % 3,
        error = error
    )

    @Test
    fun `a run is stored with every field and read back newest first`() = runTest {
        val error = SyncError(SyncErrorKind.SERVER, 503, "ServiceUnavailableError", "The sync server is unavailable (HTTP 503)")
        val runs = listOf(run(0), run(1, SyncRunResult.FAILED, error), run(2, SyncRunResult.PULLED_ONLY), run(3, SyncRunResult.CANCELLED))

        runs.forEach(history::syncEnded)

        assertEquals(runs.reversed(), history.runs.first())
    }

    @Test
    fun `only the newest runs are kept`() = runTest {
        val runs = (0 until SyncHistory.LIMIT + 5).map { run(it) }

        runs.forEach(history::syncEnded)

        assertEquals(runs.takeLast(SyncHistory.LIMIT).reversed(), history.runs.first())
        assertEquals(SyncHistory.LIMIT.toLong(), database.sync_runQueries.newest(1_000).executeAsList().size.toLong(), "pruned on insert")
    }

    @Test
    fun `a wipe of the user's data clears the history`() = runTest {
        history.syncEnded(run(0))

        PersistenceResetRepositoryImpl(database).deleteAllData()

        assertEquals(emptyList(), history.runs.first())
    }

    @Test
    fun `a row with a value this version doesn't know is skipped`() = runTest {
        history.syncEnded(run(0))
        database.sync_runQueries.insert(0, 0, "FROM_THE_FUTURE", "SUCCEEDED", 1, 0, 0, 0, null, null, null, null)

        assertEquals(listOf(run(0)), history.runs.first())
    }
}

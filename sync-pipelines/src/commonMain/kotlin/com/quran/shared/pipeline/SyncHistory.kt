package com.quran.shared.pipeline

import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.pipeline.sync.SyncRunObserver
import com.quran.shared.syncv2.db.Sync_run
import com.quran.shared.syncv2.db.SyncV2Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * Records each run that ends in `sync_run`, keeping the newest [LIMIT], for [QuranDataService.syncHistory]. The
 * sync engine's wipe (a logout, an account switch) clears it with the rest of the sync state.
 */
internal class SyncHistory(private val database: SyncV2Database) : SyncRunObserver {
    private val logger = Logger.withTag("SyncHistory")
    private val queries get() = database.sync_runQueries

    /** The newest runs first. */
    val runs: Flow<List<SyncRun>> = queries.newest(LIMIT.toLong()).asFlow()
        .map { query -> withContext(Dispatchers.IO) { query.executeAsList().mapNotNull(::toRun) } }

    /** Records [run]. A failure to record it is logged and never fails the sync. */
    override fun syncEnded(run: SyncRun) {
        try {
            database.transaction {
                queries.insert(
                    started_at = run.startedAt.fromPlatform().toEpochMilliseconds(),
                    ended_at = run.endedAt.fromPlatform().toEpochMilliseconds(),
                    triggered_by = run.trigger.name,
                    result = run.result.name,
                    attempts = run.attempts.toLong(),
                    pushed = run.pushed.toLong(),
                    pulled = run.pulled.toLong(),
                    held = run.held.toLong(),
                    error_kind = run.error?.kind?.name,
                    error_http_status = run.error?.httpStatus?.toLong(),
                    error_server_code = run.error?.serverCode,
                    error_message = run.error?.message
                )
                queries.prune(LIMIT.toLong())
            }
        } catch (e: Exception) {
            logger.e(e) { "Could not record a ${run.result} sync run" }
        }
    }

    /** A stored run; null for one written with a value this version doesn't know. */
    private fun toRun(row: Sync_run): SyncRun? {
        val trigger = SyncRunTrigger.entries.firstOrNull { it.name == row.triggered_by } ?: return null
        val result = SyncRunResult.entries.firstOrNull { it.name == row.result } ?: return null
        val error = row.error_kind?.let { kind ->
            SyncError(
                kind = SyncErrorKind.entries.firstOrNull { it.name == kind } ?: SyncErrorKind.UNKNOWN,
                httpStatus = row.error_http_status?.toInt(),
                serverCode = row.error_server_code,
                message = row.error_message.orEmpty()
            )
        }
        return SyncRun(
            startedAt = Instant.fromEpochMilliseconds(row.started_at).toPlatform(),
            endedAt = Instant.fromEpochMilliseconds(row.ended_at).toPlatform(),
            trigger = trigger,
            result = result,
            attempts = row.attempts.toInt(),
            pushed = row.pushed.toInt(),
            pulled = row.pulled.toInt(),
            held = row.held.toInt(),
            error = error
        )
    }

    companion object {
        /** How many runs the history keeps. */
        const val LIMIT = 50
    }
}

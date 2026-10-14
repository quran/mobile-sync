package com.quran.shared.persistence.repository.note

import com.quran.shared.mutations.trimLikeServer
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.NoteRange

/** A note row with its ranges in order (ranges never change after the note is created). */
internal data class StoredNote(
    val localId: Long,
    val body: String,
    val ranges: List<NoteRange>,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?
)

/**
 * The one write path for notes, so every local write (the app's and the importer's) keeps the same rules. Callers run
 * writes in a transaction.
 */
internal class NoteStore(private val database: SyncV2Database) {
    private val queries get() = database.notesQueries

    /**
     * Adds a note with its body trimmed like the server. Its creation time is [createdAt], or the first millisecond
     * after it that no other note on this device has: with the ranges, it is how the sync engine recognises a CREATE
     * whose response was lost.
     */
    fun insert(body: String, ranges: List<NoteRange>, createdAt: Long, updatedAt: Long = createdAt): Long {
        require(ranges.isNotEmpty()) { "A note needs a verse range." }
        return write(body, ranges, queries.freeCreatedAt(createdAt).executeAsOne().free ?: createdAt, updatedAt)
    }

    /**
     * A writer for many new notes in one transaction (an import), with [insert]'s rules, that finds every creation time
     * in memory (see [CreationTimes]). Notes may only be added through it while it is in use.
     */
    fun batch(): Batch = Batch(CreationTimes(queries.createdAts().executeAsList()))

    inner class Batch internal constructor(private val times: CreationTimes) {
        fun insert(body: String, ranges: List<NoteRange>, createdAt: Long, updatedAt: Long = createdAt): Long {
            require(ranges.isNotEmpty()) { "A note needs a verse range." }
            return write(body, ranges, times.take(createdAt), updatedAt)
        }
    }

    private fun write(body: String, ranges: List<NoteRange>, created: Long, updatedAt: Long): Long {
        queries.insert(body.trimLikeServer(), created, maxOf(updatedAt, created), null)
        val localId = queries.lastInsertId().executeAsOne()
        ranges.forEachIndexed { position, range ->
            queries.insertRange(
                localId, position.toLong(),
                range.startSura.toLong(), range.startAyah.toLong(), range.endSura.toLong(), range.endAyah.toLong()
            )
        }
        return localId
    }

    /** Edits the body; the ranges and the creation time never change. */
    fun updateBody(localId: Long, body: String, updatedAt: Long) {
        queries.updateBody(body.trimLikeServer(), updatedAt, localId)
    }

    fun delete(localId: Long) {
        queries.deleteRanges(localId)
        queries.delete(localId)
    }

    fun get(localId: Long): StoredNote? =
        queries.get(localId).executeAsOneOrNull()?.let { row ->
            StoredNote(row.local_id, row.body, rangesOf(row.local_id), row.created_at, row.updated_at, row.archived_at)
        }

    /** Every note, archived ones included, with its ranges. */
    fun all(): List<StoredNote> {
        val ranges = rangesByNote()
        return queries.all().executeAsList().filter { ranges[it.local_id] != null }.map { row ->
            StoredNote(row.local_id, row.body, ranges.getValue(row.local_id), row.created_at, row.updated_at, row.archived_at)
        }
    }

    /** The notes app-facing queries show: every note except the ones archived on QuranReflect, newest first. */
    fun active(): List<StoredNote> {
        val ranges = rangesByNote()
        return queries.active().executeAsList().filter { ranges[it.local_id] != null }.map { row ->
            StoredNote(row.local_id, row.body, ranges[row.local_id].orEmpty(), row.created_at, row.updated_at, row.archived_at)
        }
    }

    private fun rangesByNote(): Map<Long, List<NoteRange>> = queries.allRanges().executeAsList().groupBy({ it.note_local_id }) {
        NoteRange(it.start_sura.toInt(), it.start_ayah.toInt(), it.end_sura.toInt(), it.end_ayah.toInt())
    }

    private fun rangesOf(localId: Long): List<NoteRange> = queries.rangesOf(localId).executeAsList().map {
        NoteRange(it.start_sura.toInt(), it.start_ayah.toInt(), it.end_sura.toInt(), it.end_ayah.toInt())
    }
}

package com.quran.shared.syncv2.types

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.ServerTextLimit
import com.quran.shared.mutations.escapeServerText
import com.quran.shared.mutations.trimLikeServer
import com.quran.shared.mutations.unescapeServerText
import com.quran.shared.syncv2.Change
import com.quran.shared.syncv2.Field
import com.quran.shared.syncv2.OutgoingMutation
import com.quran.shared.syncv2.Refs
import com.quran.shared.syncv2.RemoteEntry
import com.quran.shared.syncv2.RemoteItem
import com.quran.shared.syncv2.State
import com.quran.shared.syncv2.SyncType
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.epochMillis
import com.quran.shared.syncv2.get
import com.quran.shared.syncv2.getOrNull
import com.quran.shared.syncv2.isoTime
import com.quran.shared.syncv2.stateOf
import com.quran.shared.syncv2.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A verse range, `start..end` inclusive, written like the server (`2:255-2:256`). */
data class NoteRange(val startSura: Int, val startAyah: Int, val endSura: Int, val endAyah: Int) {
    override fun toString() = "$startSura:$startAyah-$endSura:$endAyah"

    /** Both ends exist and the range doesn't run backwards (the server answers 500 otherwise). */
    val isValid: Boolean
        get() = Verses.exists(startSura, startAyah) && Verses.exists(endSura, endAyah) &&
            (startSura < endSura || (startSura == endSura && startAyah <= endAyah))

    companion object {
        private val PATTERN = Regex("^(\\d+):(\\d+)-(\\d+):(\\d+)$")

        fun parse(text: String): NoteRange? = PATTERN.matchEntire(text)?.groupValues?.let {
            NoteRange(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt())
        }
    }
}

/** [archivedAt]: archived on QuranReflect (a 30-day trash); the note still syncs, and app-facing queries hide it. */
data class Note(
    val localId: Long,
    val body: String,
    val ranges: List<NoteRange>,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long? = null
)

/** Plain CRUD over `note` and `note_range`. Ranges are set once, on create. */
class NotesRepository(private val database: SyncV2Database) {
    private val type = NotesType(database)

    fun notes(): List<Note> = type.readAll().map { (localId, state) -> NotesType.toNote(localId, state) }

    /**
     * Adds a note; the body is stored trimmed like the server. [at] becomes the note's `clientCreatedAt`, or the
     * first millisecond after it that no other note has (one query): with the ranges, it is how a lost CREATE is
     * recognised. Every local create (an importer too) must come through here.
     */
    fun addNote(body: String, ranges: List<NoteRange>, at: Long): Long = database.transactionWithResult {
        require(ranges.isNotEmpty()) { "a note needs a range" }
        val createdAt = database.notesQueries.freeCreatedAt(at).executeAsOne().free ?: at
        type.insert(NotesType.noteState(body.trimLikeServer(), ranges, createdAt, createdAt))
    }

    fun updateNote(localId: Long, body: String, at: Long) = database.transaction {
        val note = notes().single { it.localId == localId }
        type.update(localId, NotesType.noteState(body.trimLikeServer(), note.ranges, note.createdAt, at, note.archivedAt))
    }

    fun deleteNote(localId: Long) = type.delete(localId)
}

/**
 * NOTE: no natural key (equal notes are separate notes, and the server never upserts them). A lost CREATE is
 * recognised by [outboxKey], `(clientCreatedAt, ranges)`: both are immutable, and the body is left out so a note
 * another device edited since still matches. UPDATE sends only the body and `clientUpdatedAt` (S7); ranges never
 * change (S4). The body is sent escaped like the server's sanitize-html and unescaped on GET. `archivedAt` (archived on
 * QuranReflect, S13) is a field like any other: an archived note stays a row, its edits still sync (the server's UPDATE
 * accepts them), and an unarchive brings it back. The client never sends it.
 */
class NotesType(private val database: SyncV2Database) : SyncType {
    private val queries get() = database.notesQueries
    private val logger = Logger.withTag("SyncV2Notes")
    override val name = "NOTE"
    override val ignoredFields = setOf(UPDATED_AT.name)

    override fun outboxKey(state: State) = "${state[CREATED_AT]}|${state[RANGES]}"
    override fun clock(state: State) = state[UPDATED_AT]

    override fun readAll(): Map<Long, State> {
        val ranges = queries.allRanges().executeAsList().groupBy({ it.note_local_id }) {
            NoteRange(it.start_sura.toInt(), it.start_ayah.toInt(), it.end_sura.toInt(), it.end_ayah.toInt())
        }
        return queries.all().executeAsList().associate {
            it.local_id to noteState(it.body, ranges[it.local_id].orEmpty(), it.created_at, it.updated_at, it.archived_at)
        }
    }

    override fun insert(state: State, localId: Long?): Long = database.transactionWithResult {
        val id = if (localId != null) {
            queries.insertWithId(localId, state[BODY], state[CREATED_AT], state[UPDATED_AT], state.getOrNull(ARCHIVED_AT))
            localId
        } else {
            queries.insert(state[BODY], state[CREATED_AT], state[UPDATED_AT], state.getOrNull(ARCHIVED_AT))
            queries.lastInsertId().executeAsOne()
        }
        writeRanges(id, state)
        id
    }

    override fun update(localId: Long, state: State) = database.transaction {
        queries.update(state[BODY], state[CREATED_AT], state[UPDATED_AT], state.getOrNull(ARCHIVED_AT), localId)
        // Ranges never change through the app; a server state that differs (it can't today) is still taken.
        if (rangesOf(localId) != state[RANGES]) writeRanges(localId, state)
    }

    override fun delete(localId: Long) = database.transaction {
        queries.deleteRanges(localId)
        queries.delete(localId)
    }

    override fun fromRemote(entries: List<RemoteEntry>, refs: Refs): List<RemoteItem> =
        entries.filter { it.resource == name }.mapNotNull(::fromRemote)

    private fun fromRemote(entry: RemoteEntry): RemoteItem? {
        val id = entry.resourceId ?: return null
        if (entry.type == "DELETE") return RemoteItem.Deleted(id, entry.timestamp)
        // `{}`: the note vanished between the server's queries.
        val body = entry.data.string("body")?.unescapeServerText() ?: return null
        val ranges = (entry.data["ranges"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull?.let(NoteRange::parse) }
        if (ranges.isNullOrEmpty() || ranges.any { it == null }) {
            logger.w { "Skipping note $id: unreadable ranges ${entry.data["ranges"]}" }
            return null
        }
        val createdAt = entry.data.epochMillis("clientCreatedAt") ?: entry.timestamp
        val updatedAt = entry.data.epochMillis("clientUpdatedAt") ?: createdAt
        val archivedAt = entry.data.epochMillis("archivedAt")
        return RemoteItem.Upsert(id, noteState(body, ranges.filterNotNull(), createdAt, updatedAt, archivedAt), entry.timestamp)
    }

    override fun mutation(change: Change, refs: Refs): OutgoingMutation = when (change) {
        is Change.Create -> OutgoingMutation("CREATE", name, null, buildJsonObject {
            put("body", change.state[BODY].escapeServerText())
            put("ranges", JsonArray(ranges(change.state).map { JsonPrimitive(it.toString()) }))
            put("clientCreatedAt", isoTime(change.state[CREATED_AT]))
            put("clientUpdatedAt", isoTime(change.state[UPDATED_AT]))
        })
        is Change.Update -> OutgoingMutation("UPDATE", name, change.remoteId, buildJsonObject {
            put("body", change.state[BODY].escapeServerText())
            put("clientUpdatedAt", isoTime(change.state[UPDATED_AT]))
        })
        is Change.Delete -> OutgoingMutation("DELETE", name, change.remoteId, null)
    }

    override fun validate(change: Change): String? = when {
        change is Change.Delete -> null
        !ServerTextLimit.NOTE_BODY.accepts(change.state[BODY]) -> "BODY_LENGTH"
        change is Change.Create && ranges(change.state).let { it.isEmpty() || it.any { range -> !range.isValid } } -> "INVALID_RANGE"
        else -> null
    }

    private fun writeRanges(localId: Long, state: State) {
        queries.deleteRanges(localId)
        ranges(state).forEachIndexed { position, range ->
            queries.insertRange(
                localId, position.toLong(),
                range.startSura.toLong(), range.startAyah.toLong(), range.endSura.toLong(), range.endAyah.toLong()
            )
        }
    }

    private fun rangesOf(localId: Long) = queries.rangesOf(localId).executeAsList()
        .joinToString(",") { "${it.start_sura}:${it.start_ayah}-${it.end_sura}:${it.end_ayah}" }

    companion object {
        private val BODY = Field.string("body")
        /** The ranges in order, as the server writes them (`2:255-2:256,3:18-3:18`). */
        private val RANGES = Field.string("ranges")
        private val CREATED_AT = Field.long("createdAt")
        private val UPDATED_AT = Field.long("updatedAt")
        /** Always present; JSON null while the note isn't archived. */
        private val ARCHIVED_AT = Field.long("archivedAt")

        fun noteState(body: String, ranges: List<NoteRange>, createdAt: Long, updatedAt: Long, archivedAt: Long? = null): State = stateOf(
            BODY(body), RANGES(ranges.joinToString(",")), CREATED_AT(createdAt), UPDATED_AT(updatedAt),
            archivedAt?.let { ARCHIVED_AT(it) } ?: (ARCHIVED_AT.name to JsonNull)
        )

        private fun ranges(state: State): List<NoteRange> = state[RANGES].split(",").mapNotNull(NoteRange::parse)

        internal fun toNote(localId: Long, state: State) =
            Note(localId, state[BODY], ranges(state), state[CREATED_AT], state[UPDATED_AT], state.getOrNull(ARCHIVED_AT))
    }
}

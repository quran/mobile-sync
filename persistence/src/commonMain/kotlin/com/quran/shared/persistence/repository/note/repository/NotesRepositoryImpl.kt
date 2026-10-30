package com.quran.shared.persistence.repository.note.repository

import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.trimLikeServer
import com.quran.shared.persistence.TextLengthField
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.model.NoteRange
import com.quran.shared.persistence.repository.note.NoteStore
import com.quran.shared.persistence.repository.note.StoredNote
import com.quran.shared.persistence.requireServerTextLength
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.currentPlatformDateTime
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.NoteRange as StoredNoteRange
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.native.HiddenFromObjC
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
@HiddenFromObjC
class NotesRepositoryImpl(
    private val database: SyncV2Database
) : NotesRepository {

    private val logger = Logger.withTag("NotesRepository")
    private val store = NoteStore(database)

    override suspend fun getAllNotes(): List<Note> = withContext(Dispatchers.IO) {
        store.active().map { it.toNote() }
    }

    override fun getNotesFlow(): Flow<List<Note>> =
        database.notesQueries.active().asFlow().map {
            withContext(Dispatchers.IO) { store.active().map { note -> note.toNote() } }
        }

    override suspend fun addNote(body: String, startSura: Int, startAyah: Int, endSura: Int, endAyah: Int): Note =
        addNote(body, startSura, startAyah, endSura, endAyah, currentPlatformDateTime())

    override suspend fun addNote(
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int,
        timestamp: PlatformDateTime
    ): Note {
        logger.i { "Adding note for range=$startSura:$startAyah-$endSura:$endAyah" }
        requireServerTextLength(TextLengthField.NOTE_BODY, body)
        val at = timestamp.toEpochMillisecondsFromPlatform()
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val localId = store.insert(body, listOf(StoredNoteRange(startSura, startAyah, endSura, endAyah)), at)
                requireNotNull(store.get(localId)) { "Expected note after insert." }.toNote()
            }
        }
    }

    override suspend fun updateNote(id: String, body: String): Note = updateNote(id, body, currentPlatformDateTime())

    override suspend fun updateNote(id: String, body: String, timestamp: PlatformDateTime): Note {
        logger.i { "Updating note id=$id" }
        val at = timestamp.toEpochMillisecondsFromPlatform()
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val localId = id.toLongOrNull()
                val existing = localId?.let(store::get)
                requireNotNull(existing) { "Expected note id=$id before update." }
                // Only a changed body is checked, so a legacy note outside the limits can still be saved unchanged.
                if (existing.body != body.trimLikeServer()) requireServerTextLength(TextLengthField.NOTE_BODY, body)
                store.updateBody(existing, body, at)
                requireNotNull(store.get(existing.localId)) { "Expected note id=$id after update." }.toNote()
            }
        }
    }

    override suspend fun deleteNote(id: String): Boolean {
        logger.i { "Deleting note id=$id" }
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val note = id.toLongOrNull()?.let(store::get) ?: return@transactionWithResult false
                store.delete(note.localId)
                true
            }
        }
    }
}

internal fun StoredNote.toNote(): Note = Note(
    body = body,
    ranges = ranges.map { NoteRange(it.startSura, it.startAyah, it.endSura, it.endAyah) },
    lastUpdated = Instant.fromEpochMilliseconds(updatedAt).toPlatform(),
    id = localId.toString()
)

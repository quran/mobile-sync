package com.quran.shared.persistence.repository.note.repository

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteNote
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.repository.note.extension.toNote
import com.quran.shared.persistence.repository.note.extension.toNoteMutation
import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS
import com.quran.shared.persistence.util.fromPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class NotesRepositoryImpl(
    private val database: QuranDatabase
) : NotesRepository, NotesSynchronizationRepository {

    private val logger = Logger.withTag("NotesRepository")
    private val notesQueries = lazy { database.notesQueries }

    override suspend fun getAllNotes(): List<Note> {
        return withContext(Dispatchers.IO) {
            notesQueries.value.getNotes()
                .executeAsList()
                .map { it.toNote() }
        }
    }

    override fun getNotesFlow(): Flow<List<Note>> {
        return notesQueries.value.getNotes()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { it.toNote() }
            }
    }

    override suspend fun addNote(body: String, startAyahId: Long, endAyahId: Long): Note {
        logger.i { "Adding note for range=$startAyahId-$endAyahId" }
        return withContext(Dispatchers.IO) {
            notesQueries.value.addNewNote(
                note = body,
                start_ayah_id = startAyahId,
                end_ayah_id = endAyahId
            )
            val record = notesQueries.value.getLastInsertedNote()
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected note after insert." }
            record.toNote()
        }
    }

    override suspend fun updateNote(localId: String, body: String, startAyahId: Long, endAyahId: Long): Note {
        logger.i { "Updating note localId=$localId" }
        return withContext(Dispatchers.IO) {
            notesQueries.value.updateNote(
                note = body,
                start_ayah_id = startAyahId,
                end_ayah_id = endAyahId,
                id = localId.toLong()
            )
            val record = notesQueries.value.getNoteByLocalId(localId.toLong())
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected note localId=$localId after update." }
            record.toNote()
        }
    }

    override suspend fun deleteNote(localId: String): Boolean {
        logger.i { "Deleting note localId=$localId" }
        withContext(Dispatchers.IO) {
            notesQueries.value.deleteNote(id = localId.toLong())
        }
        return true
    }

    override suspend fun fetchMutatedNotes(lastModified: Long): List<LocalModelMutation<Note>> {
        return withContext(Dispatchers.IO) {
            notesQueries.value.getUnsyncedNotes(last_modified = lastModified)
                .executeAsList()
                .map { it.toNoteMutation() }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteNote>>,
        localMutationsToClear: List<LocalModelMutation<Note>>
    ) {
        logger.i {
            "Applying note remote changes with ${updatesToPersist.size} updates " +
                "and clearing ${localMutationsToClear.size} local mutations"
        }
        return withContext(Dispatchers.IO) {
            database.transaction {
                localMutationsToClear.forEach { local ->
                    notesQueries.value.clearLocalMutationFor(id = local.localID.toLong())
                }

                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED, Mutation.MODIFIED -> applyRemoteNoteUpsert(remote)
                        Mutation.DELETED -> applyRemoteNoteDeletion(remote)
                    }
                }
            }
        }
    }

    private fun applyRemoteNoteUpsert(remote: RemoteModelMutation<RemoteNote>) {
        val model = remote.model
        val body = model.body
        val startAyahId = model.startAyahId
        val endAyahId = model.endAyahId
        if (body.isNullOrEmpty() || startAyahId == null || endAyahId == null) {
            logger.w { "Skipping remote note mutation without body or ranges: remoteId=${remote.remoteID}" }
            return
        }

        val updatedAt = model.lastUpdated.fromPlatform().toEpochMilliseconds()
        notesQueries.value.persistRemoteNote(
            remote_id = remote.remoteID,
            note = body,
            start_ayah_id = startAyahId,
            end_ayah_id = endAyahId,
            created_at = updatedAt,
            modified_at = updatedAt
        )
    }

    private fun applyRemoteNoteDeletion(remote: RemoteModelMutation<RemoteNote>) {
        notesQueries.value.deleteRemoteNote(remote_id = remote.remoteID)
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        if (remoteIDs.isEmpty()) {
            return emptyMap()
        }

        return withContext(Dispatchers.IO) {
            val existentIDs = mutableSetOf<String>()
            remoteIDs.chunked(SQLITE_MAX_BIND_PARAMETERS).forEach { chunk ->
                existentIDs.addAll(
                    notesQueries.value.checkRemoteIDsExistence(chunk)
                        .executeAsList()
                        .mapNotNull { it.remote_id }
                )
            }

            remoteIDs.associateWith { existentIDs.contains(it) }
        }
    }
}

package com.quran.shared.persistence.repository.note.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LOCAL_MUTATION_ENTITY_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteNote
import com.quran.shared.persistence.model.DatabaseNote
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard
import com.quran.shared.persistence.repository.buildRemoteResourceExistenceMap
import com.quran.shared.persistence.repository.note.extension.toNote
import com.quran.shared.persistence.repository.note.extension.toNoteMutation
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toEpochMillisecondsOrNull
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
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

    override suspend fun addNote(body: String, startSura: Int, startAyah: Int, endSura: Int, endAyah: Int): Note {
        return addNoteWithTimestampMillis(body, startSura, startAyah, endSura, endAyah, timestampMillis = null)
    }

    override suspend fun addNote(
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int,
        timestamp: PlatformDateTime
    ): Note {
        return addNoteWithTimestampMillis(
            body,
            startSura,
            startAyah,
            endSura,
            endAyah,
            timestamp.toEpochMillisecondsOrNull()
        )
    }

    private suspend fun addNoteWithTimestampMillis(
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int,
        timestampMillis: Long?
    ): Note {
        logger.i { "Adding note for range=$startSura:$startAyah-$endSura:$endAyah" }
        return withContext(Dispatchers.IO) {
            val startAyahId = requireAyahId(startSura, startAyah)
            val endAyahId = requireAyahId(endSura, endAyah)
            var inserted: Note? = null
            database.transaction {
                notesQueries.value.addNewNote(
                    note = body,
                    start_ayah_id = startAyahId.toLong(),
                    end_ayah_id = endAyahId.toLong(),
                    timestamp = timestampMillis
                )
                val record = notesQueries.value.getLastInsertedNote().executeAsOneOrNull()
                requireNotNull(record) { "Expected note after insert." }
                inserted = record.toNote()
            }
            requireNotNull(inserted)
        }
    }

    override suspend fun updateNote(
        localId: String,
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int
    ): Note {
        return updateNoteWithTimestampMillis(
            localId,
            body,
            startSura,
            startAyah,
            endSura,
            endAyah,
            timestampMillis = null
        )
    }

    override suspend fun updateNote(
        localId: String,
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int,
        timestamp: PlatformDateTime
    ): Note {
        return updateNoteWithTimestampMillis(
            localId,
            body,
            startSura,
            startAyah,
            endSura,
            endAyah,
            timestamp.toEpochMillisecondsOrNull()
        )
    }

    private suspend fun updateNoteWithTimestampMillis(
        localId: String,
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int,
        timestampMillis: Long?
    ): Note {
        logger.i { "Updating note localId=$localId" }
        return withContext(Dispatchers.IO) {
            val startAyahId = requireAyahId(startSura, startAyah)
            val endAyahId = requireAyahId(endSura, endAyah)
            notesQueries.value.updateNote(
                note = body,
                start_ayah_id = startAyahId.toLong(),
                end_ayah_id = endAyahId.toLong(),
                id = localId.toLong(),
                timestamp = timestampMillis
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
            notesQueries.value.deleteNote(
                id = localId.toLong()
            )
        }
        return true
    }

    override suspend fun fetchMutatedNotes(lastModified: Long): List<LocalModelMutation<Note>> {
        return withContext(Dispatchers.IO) {
            notesQueries.value.getUnsyncedNotes()
                .executeAsList()
                .map { it.toNoteMutation() }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteNote>>,
        localMutationsToClear: List<LocalModelMutation<Note>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) {
        logger.i {
            "Applying note remote changes with ${updatesToPersist.size} updates " +
                "and clearing ${localMutationsToClear.size} local mutations"
        }
        return withContext(Dispatchers.IO) {
            writeBoundaryGuard.checkWriteBoundary()
            database.transaction {
                localMutationsToClear.forEach { local ->
                    if (local.mutation != Mutation.CREATED && ackMatchesCurrentRow(local)) {
                        clearLocalMutation(local)
                    }
                }

                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED, Mutation.MODIFIED -> applyRemoteNoteUpsert(
                            remote = remote
                        )
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
        val existing = notesQueries.value.getNoteByRemoteId(remote.remoteID)
            .executeAsOneOrNull()
        if (existing?.hasPendingLocalMutation() == true) {
            logger.i { "Skipping remote note upsert for pending local row: remoteId=${remote.remoteID}" }
            return
        }
        if (remote.mutation == Mutation.CREATED && existing == null) {
            val createdAck = remote.createdAckOrNull()
            if (createdAck != null && attachRemoteIdForCreatedAck(remote, createdAck, updatedAt)) {
                return
            }
            if (attachRemoteIdForSemanticReplay(remote, updatedAt)) {
                return
            }
        }
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
        val row = notesQueries.value.getNoteByRemoteId(remote.remoteID)
            .executeAsOneOrNull()
        if (row?.hasPendingLocalMutation() == true) {
            logger.i { "Skipping remote note deletion for pending local row: remoteId=${remote.remoteID}" }
            return
        }
        notesQueries.value.deleteRemoteNote(remote_id = remote.remoteID)
    }

    private fun requireAyahId(sura: Int, ayah: Int): Int {
        return requireNotNull(QuranData.getAyahIdOrNull(sura, ayah)) {
            "Invalid note ayah: $sura:$ayah"
        }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        return buildRemoteResourceExistenceMap(remoteIDs) { chunk ->
            notesQueries.value.checkRemoteIDsExistence(chunk)
                .executeAsList()
                .mapNotNull { it.remote_id }
        }
    }

    private fun attachRemoteIdForCreatedAck(
        remote: RemoteModelMutation<RemoteNote>,
        ack: CreatedNoteAck,
        updatedAt: Long
    ): Boolean {
        val row = notesQueries.value.getNoteByLocalId(ack.localId)
            .executeAsOneOrNull()
        if (row?.remote_id != null) {
            return false
        }
        notesQueries.value.attachRemoteNoteIdForCreatedAck(
            local_id = ack.localId,
            remote_id = remote.remoteID,
            pending_version = ack.pendingVersion,
            modified_at = updatedAt
        )
        val attached = notesQueries.value.getNoteByLocalId(ack.localId)
            .executeAsOneOrNull()
        return attached?.remote_id == remote.remoteID
    }

    private fun attachRemoteIdForSemanticReplay(
        remote: RemoteModelMutation<RemoteNote>,
        updatedAt: Long
    ): Boolean {
        val model = remote.model
        if (!model.semanticReplayEligible) {
            return false
        }
        val body = model.body ?: return false
        val startAyahId = model.startAyahId ?: return false
        val endAyahId = model.endAyahId ?: return false
        val activeCandidates = notesQueries.value.getPendingActiveCreatedNotesByContent(
            note = body,
            start_ayah_id = startAyahId,
            end_ayah_id = endAyahId
        ).executeAsList()
        val deletedCandidates = notesQueries.value.getPendingDeletedCreatedNotesByContent(
            note = body,
            start_ayah_id = startAyahId,
            end_ayah_id = endAyahId
        ).executeAsList()
        val candidates = activeCandidates + deletedCandidates
        if (candidates.size != 1) {
            return false
        }
        return attachRemoteIdForSemanticReplay(remote, candidates.single(), updatedAt)
    }

    private fun attachRemoteIdForSemanticReplay(
        remote: RemoteModelMutation<RemoteNote>,
        row: DatabaseNote,
        updatedAt: Long
    ): Boolean {
        if (row.remote_id != null || row.pendingMutation() != Mutation.CREATED) {
            return false
        }
        notesQueries.value.attachRemoteNoteIdForSemanticReplay(
            local_id = row.local_id,
            remote_id = remote.remoteID,
            modified_at = updatedAt
        )
        val attached = notesQueries.value.getNoteByLocalId(row.local_id)
            .executeAsOneOrNull()
        return attached?.remote_id == remote.remoteID
    }

    private fun clearLocalMutation(local: LocalModelMutation<Note>) {
        val ack = local.ack ?: return
        notesQueries.value.clearLocalMutationFor(
            id = local.localID.toLong(),
            pending_version = ack.observedPendingVersion,
            pending_op = ack.observedPendingOp.name
        )
    }

    private fun ackMatchesCurrentRow(local: LocalModelMutation<Note>): Boolean {
        val ack = local.ack ?: return false
        if (ack.localID != local.localID ||
            ack.resource != LocalMutationResource.NOTE ||
            ack.facet != LOCAL_MUTATION_ENTITY_FACET ||
            ack.observedPendingOp != local.mutation) {
            return false
        }
        val row = notesQueries.value.getNoteByLocalId(local.localID.toLong())
            .executeAsOneOrNull()
            ?: return false
        return row.pending_version == ack.observedPendingVersion &&
            row.pendingMutation() == ack.observedPendingOp
    }

    private fun DatabaseNote.pendingMutation(): Mutation? {
        return when {
            remote_id == null -> Mutation.CREATED
            deleted == 1L -> Mutation.DELETED
            is_edited == 1L -> Mutation.MODIFIED
            else -> null
        }
    }

    private fun DatabaseNote.hasPendingLocalMutation(): Boolean = pendingMutation() != null

    private fun RemoteModelMutation<RemoteNote>.createdAckOrNull(): CreatedNoteAck? {
        val ack = ack ?: return null
        if (mutation != Mutation.CREATED ||
            ack.resource != LocalMutationResource.NOTE ||
            ack.facet != LOCAL_MUTATION_ENTITY_FACET ||
            ack.observedPendingOp != Mutation.CREATED) {
            return null
        }
        return CreatedNoteAck(
            localId = ack.localID.toLong(),
            pendingVersion = ack.observedPendingVersion
        )
    }

    private data class CreatedNoteAck(
        val localId: Long,
        val pendingVersion: Long
    )
}

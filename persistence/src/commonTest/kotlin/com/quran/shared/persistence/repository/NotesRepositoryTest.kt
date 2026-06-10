package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.RemoteNote
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class NotesRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: NotesRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        repository = NotesRepositoryImpl(database)
    }

    @Test
    fun `addNote returns inserted note`() = runTest {
        val note = repository.addNote(
            body = "test note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13
        )

        assertEquals("test note", note.body)
        assertEquals(2, note.startSura)
        assertEquals(13, note.startAyah)
        assertEquals(2, note.endSura)
        assertEquals(13, note.endAyah)
    }

    @Test
    fun `addNote respects explicit timestamp`() = runTest {
        val note = repository.addNote(
            body = "test note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_234L)
        )
        val record = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()

        assertEquals(1_700_000_001_234L, note.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1_700_000_001_234L, record.created_at)
        assertEquals(1_700_000_001_234L, record.modified_at)
    }

    @Test
    fun `updateNote respects explicit timestamp and preserves created_at`() = runTest {
        val note = repository.addNote("test note", 2, 13, 2, 13, timestamp(1_700_000_001_000L))

        val updated = repository.updateNote(
            note.localId,
            "updated note",
            2,
            14,
            2,
            14,
            timestamp(1_700_000_002_345L)
        )
        val record = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()

        assertEquals(1_700_000_002_345L, updated.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1_700_000_001_000L, record.created_at)
        assertEquals(1_700_000_002_345L, record.modified_at)
    }

    @Test
    fun `deleteNote preserves timestamp for remote rows`() = runTest {
        database.notesQueries.persistRemoteNote(
            remote_id = "remote-note-id",
            note = "test note",
            start_ayah_id = QuranData.getAyahId(2, 13).toLong(),
            end_ayah_id = QuranData.getAyahId(2, 14).toLong(),
            created_at = 1_700_000_001_000L,
            modified_at = 1_700_000_001_000L
        )
        val note = database.notesQueries.getNoteByLocalId(1L).executeAsOne()

        repository.deleteNote(note.local_id.toString())

        val mutation = repository.fetchMutatedNotes(0).single()
        val record = database.notesQueries.getNoteByLocalId(note.local_id).executeAsOne()
        assertEquals(Mutation.DELETED, mutation.mutation)
        assertEquals(1_700_000_001_000L, mutation.model.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1_700_000_001_000L, record.modified_at)
    }

    @Test
    fun `applyRemoteChanges clears note ACK when pending version still matches`() = runTest {
        database.notesQueries.persistRemoteNote(
            remote_id = "remote-note-id",
            note = "test note",
            start_ayah_id = QuranData.getAyahId(2, 13).toLong(),
            end_ayah_id = QuranData.getAyahId(2, 13).toLong(),
            created_at = 1_700_000_001_000L,
            modified_at = 1_700_000_001_000L
        )
        val note = database.notesQueries.getNoteByRemoteId("remote-note-id").executeAsOne()
        repository.updateNote(note.local_id.toString(), "synced note", 2, 14, 2, 14, timestamp(1_700_000_002_000L))
        val mutation = repository.fetchMutatedNotes(0).single()

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "synced note",
                        startAyahId = QuranData.getAyahId(2, 14).toLong(),
                        endAyahId = QuranData.getAyahId(2, 14).toLong(),
                        lastUpdated = timestamp(1_700_000_002_000L)
                    ),
                    remoteID = "remote-note-id",
                    mutation = Mutation.MODIFIED
                )
            ),
            localMutationsToClear = listOf(mutation)
        )

        val record = database.notesQueries.getNoteByRemoteId("remote-note-id").executeAsOne()
        assertEquals("synced note", record.note)
        assertEquals(0L, record.is_edited)
        assertEquals(emptyList(), repository.fetchMutatedNotes(0))
    }

    @Test
    fun `applyRemoteChanges checks write boundary before note transaction`() = runTest {
        assertFailsWith<IllegalStateException> {
            repository.applyRemoteChanges(
                updatesToPersist = listOf(
                    RemoteModelMutation(
                        model = RemoteNote(
                            body = "synced note",
                            startAyahId = QuranData.getAyahId(2, 13).toLong(),
                            endAyahId = QuranData.getAyahId(2, 13).toLong(),
                            lastUpdated = timestamp(1_700_000_002_000L)
                        ),
                        remoteID = "remote-note-id",
                        mutation = Mutation.CREATED
                    )
                ),
                localMutationsToClear = emptyList(),
                writeBoundaryGuard = PersistenceWriteBoundaryGuard {
                    throw IllegalStateException("stale epoch")
                }
            )
        }

        assertNull(database.notesQueries.getNoteByRemoteId("remote-note-id").executeAsOneOrNull())
    }

    @Test
    fun `applyRemoteChanges does not clear stale note ACK after newer local write`() = runTest {
        database.notesQueries.persistRemoteNote(
            remote_id = "remote-note-id",
            note = "test note",
            start_ayah_id = QuranData.getAyahId(2, 13).toLong(),
            end_ayah_id = QuranData.getAyahId(2, 13).toLong(),
            created_at = 1_700_000_001_000L,
            modified_at = 1_700_000_001_000L
        )
        val note = database.notesQueries.getNoteByRemoteId("remote-note-id").executeAsOne()
        repository.updateNote(note.local_id.toString(), "uploaded note", 2, 14, 2, 14, timestamp(1_700_000_002_000L))
        val staleMutation = repository.fetchMutatedNotes(0).single()
        repository.updateNote(note.local_id.toString(), "newer note", 2, 15, 2, 15, timestamp(1_700_000_003_000L))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "uploaded note",
                        startAyahId = QuranData.getAyahId(2, 14).toLong(),
                        endAyahId = QuranData.getAyahId(2, 14).toLong(),
                        lastUpdated = timestamp(1_700_000_002_000L)
                    ),
                    remoteID = "remote-note-id",
                    mutation = Mutation.MODIFIED
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.notesQueries.getNoteByRemoteId("remote-note-id").executeAsOne()
        val remaining = repository.fetchMutatedNotes(0).single()
        assertEquals("newer note", record.note)
        assertEquals(QuranData.getAyahId(2, 15).toLong(), record.start_ayah_id)
        assertEquals(1L, record.is_edited)
        assertEquals(Mutation.MODIFIED, remaining.mutation)
    }

    @Test
    fun `stale created note ACK binds remote id and leaves newer edit pending`() = runTest {
        val note = repository.addNote(
            body = "uploaded note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_000L)
        )
        val staleMutation = repository.fetchMutatedNotes(0).single()
        repository.updateNote(
            note.localId,
            "newer note",
            2,
            14,
            2,
            14,
            timestamp(1_700_000_002_000L)
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "uploaded note",
                        startAyahId = QuranData.getAyahId(2, 13).toLong(),
                        endAyahId = QuranData.getAyahId(2, 13).toLong(),
                        lastUpdated = timestamp(1_700_000_001_000L)
                    ),
                    remoteID = "remote-created-note-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedNotes(0).single()
        assertEquals("remote-created-note-id", record.remote_id)
        assertEquals("newer note", record.note)
        assertEquals(QuranData.getAyahId(2, 14).toLong(), record.start_ayah_id)
        assertEquals(1L, record.is_edited)
        assertEquals(note.localId, remaining.localID)
        assertEquals("remote-created-note-id", remaining.remoteID)
        assertEquals(Mutation.MODIFIED, remaining.mutation)
    }

    @Test
    fun `stale created note ACK binds remote id and leaves delete pending`() = runTest {
        val note = repository.addNote(
            body = "uploaded note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_000L)
        )
        val staleMutation = repository.fetchMutatedNotes(0).single()
        repository.deleteNote(note.localId)
        assertEquals(emptyList(), repository.getAllNotes())
        assertEquals(emptyList(), repository.fetchMutatedNotes(0))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "uploaded note",
                        startAyahId = QuranData.getAyahId(2, 13).toLong(),
                        endAyahId = QuranData.getAyahId(2, 13).toLong(),
                        lastUpdated = timestamp(1_700_000_001_000L)
                    ),
                    remoteID = "remote-created-note-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedNotes(0).single()
        assertEquals(emptyList(), repository.getAllNotes())
        assertEquals("remote-created-note-id", record.remote_id)
        assertEquals(1L, record.deleted)
        assertEquals("uploaded note", record.note)
        assertEquals(note.localId, remaining.localID)
        assertEquals("remote-created-note-id", remaining.remoteID)
        assertEquals(Mutation.DELETED, remaining.mutation)
    }

    @Test
    fun `deleteExisting import keeps local-created note tombstone until create ACK binds`() = runTest {
        val note = repository.addNote(
            body = "uploaded note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_000L)
        )
        val staleMutation = repository.fetchMutatedNotes(0).single()
        PersistenceImportRepositoryImpl(database).importData(
            data = PersistenceImportData(),
            deleteExisting = true
        )

        val tombstone = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()
        assertEquals(null, tombstone.remote_id)
        assertEquals(1L, tombstone.deleted)
        assertEquals(emptyList(), repository.getAllNotes())
        assertEquals(emptyList(), repository.fetchMutatedNotes(0))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "uploaded note",
                        startAyahId = QuranData.getAyahId(2, 13).toLong(),
                        endAyahId = QuranData.getAyahId(2, 13).toLong(),
                        lastUpdated = timestamp(1_700_000_001_000L)
                    ),
                    remoteID = "remote-created-note-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedNotes(0).single()
        assertEquals("remote-created-note-id", record.remote_id)
        assertEquals(1L, record.deleted)
        assertEquals(note.localId, remaining.localID)
        assertEquals("remote-created-note-id", remaining.remoteID)
        assertEquals(Mutation.DELETED, remaining.mutation)
    }

    @Test
    fun `remote created note without ACK binds unique matching local-created note`() = runTest {
        val note = repository.addNote(
            body = "same note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_000L)
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "same note",
                        startAyahId = QuranData.getAyahId(2, 13).toLong(),
                        endAyahId = QuranData.getAyahId(2, 13).toLong(),
                        lastUpdated = timestamp(1_700_000_002_000L)
                    ),
                    remoteID = "remote-created-note-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val localRecord = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()
        assertEquals("remote-created-note-id", localRecord.remote_id)
        assertEquals(1, repository.getAllNotes().size)
        assertEquals(emptyList(), repository.fetchMutatedNotes(0))
    }

    @Test
    fun `replayed remote created note does not bind duplicate local create when remote id already exists`() = runTest {
        database.notesQueries.persistRemoteNote(
            remote_id = "remote-created-note-id",
            note = "same note",
            start_ayah_id = QuranData.getAyahId(2, 13).toLong(),
            end_ayah_id = QuranData.getAyahId(2, 13).toLong(),
            created_at = 1_700_000_001_000L,
            modified_at = 1_700_000_001_000L
        )
        val duplicate = repository.addNote(
            body = "same note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_002_000L)
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "same note",
                        startAyahId = QuranData.getAyahId(2, 13).toLong(),
                        endAyahId = QuranData.getAyahId(2, 13).toLong(),
                        lastUpdated = timestamp(1_700_000_003_000L)
                    ),
                    remoteID = "remote-created-note-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val existingRemote = database.notesQueries.getNoteByRemoteId("remote-created-note-id").executeAsOne()
        val duplicateRecord = database.notesQueries.getNoteByLocalId(duplicate.localId.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedNotes(0).single()
        assertEquals("remote-created-note-id", existingRemote.remote_id)
        assertEquals(null, duplicateRecord.remote_id)
        assertEquals(duplicate.localId, remaining.localID)
        assertEquals(Mutation.CREATED, remaining.mutation)
    }

    @Test
    fun `remote created note without ACK binds deleted pending create and leaves delete pending`() = runTest {
        val note = repository.addNote(
            body = "same note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_000L)
        )
        repository.deleteNote(note.localId)
        assertEquals(emptyList(), repository.getAllNotes())
        assertEquals(emptyList(), repository.fetchMutatedNotes(0))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "same note",
                        startAyahId = QuranData.getAyahId(2, 13).toLong(),
                        endAyahId = QuranData.getAyahId(2, 13).toLong(),
                        lastUpdated = timestamp(1_700_000_002_000L)
                    ),
                    remoteID = "remote-created-note-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val localRecord = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedNotes(0).single()
        assertEquals(emptyList(), repository.getAllNotes())
        assertEquals("remote-created-note-id", localRecord.remote_id)
        assertEquals(1L, localRecord.deleted)
        assertEquals(note.localId, remaining.localID)
        assertEquals("remote-created-note-id", remaining.remoteID)
        assertEquals(Mutation.DELETED, remaining.mutation)
    }

    @Test
    fun `remote created note with active and deleted matching creates persists separately`() = runTest {
        val deleted = repository.addNote(
            body = "same note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_000L)
        )
        repository.deleteNote(deleted.localId)
        val active = repository.addNote(
            body = "same note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_002_000L)
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "same note",
                        startAyahId = QuranData.getAyahId(2, 13).toLong(),
                        endAyahId = QuranData.getAyahId(2, 13).toLong(),
                        lastUpdated = timestamp(1_700_000_003_000L)
                    ),
                    remoteID = "remote-created-note-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val deletedRecord = database.notesQueries.getNoteByLocalId(deleted.localId.toLong()).executeAsOne()
        val activeRecord = database.notesQueries.getNoteByLocalId(active.localId.toLong()).executeAsOne()
        val remoteRecord = database.notesQueries.getNoteByRemoteId("remote-created-note-id").executeAsOne()
        val remaining = repository.fetchMutatedNotes(0)
        assertEquals(null, deletedRecord.remote_id)
        assertEquals(1L, deletedRecord.deleted)
        assertEquals(null, activeRecord.remote_id)
        assertEquals(0L, activeRecord.deleted)
        assertEquals("remote-created-note-id", remoteRecord.remote_id)
        assertEquals(2, repository.getAllNotes().size)
        assertEquals(listOf(active.localId), remaining.map { it.localID })
        assertEquals(Mutation.CREATED, remaining.single().mutation)
    }

    @Test
    fun `ambiguous deleted note replay candidates persist remote note separately`() = runTest {
        val first = repository.addNote("same note", 2, 13, 2, 13, timestamp(1_700_000_001_000L))
        val second = repository.addNote("same note", 2, 13, 2, 13, timestamp(1_700_000_002_000L))
        repository.deleteNote(first.localId)
        repository.deleteNote(second.localId)

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "same note",
                        startAyahId = QuranData.getAyahId(2, 13).toLong(),
                        endAyahId = QuranData.getAyahId(2, 13).toLong(),
                        lastUpdated = timestamp(1_700_000_003_000L)
                    ),
                    remoteID = "remote-created-note-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val firstRecord = database.notesQueries.getNoteByLocalId(first.localId.toLong()).executeAsOne()
        val secondRecord = database.notesQueries.getNoteByLocalId(second.localId.toLong()).executeAsOne()
        val remoteRecord = database.notesQueries.getNoteByRemoteId("remote-created-note-id").executeAsOne()
        assertEquals(null, firstRecord.remote_id)
        assertEquals(1L, firstRecord.deleted)
        assertEquals(null, secondRecord.remote_id)
        assertEquals(1L, secondRecord.deleted)
        assertEquals("remote-created-note-id", remoteRecord.remote_id)
        assertEquals(1, repository.getAllNotes().size)
        assertEquals(emptyList(), repository.fetchMutatedNotes(0))
    }

    @Test
    fun `remote created note without single-range eligibility does not bind truncated local match`() = runTest {
        val note = repository.addNote(
            body = "same note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_000L)
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "same note",
                        startAyahId = QuranData.getAyahId(2, 13).toLong(),
                        endAyahId = QuranData.getAyahId(2, 13).toLong(),
                        lastUpdated = timestamp(1_700_000_002_000L),
                        semanticReplayEligible = false
                    ),
                    remoteID = "remote-created-note-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val localRecord = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()
        val remoteRecord = database.notesQueries.getNoteByRemoteId("remote-created-note-id").executeAsOne()
        assertEquals(null, localRecord.remote_id)
        assertEquals("remote-created-note-id", remoteRecord.remote_id)
        assertEquals(Mutation.CREATED, repository.fetchMutatedNotes(0).single().mutation)
    }

    @Test
    fun `remote created note without ACK persists separately when matching local-created note is ambiguous`() = runTest {
        repository.addNote(
            body = "same note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_000L)
        )
        repository.addNote(
            body = "same note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_500L)
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "same note",
                        startAyahId = QuranData.getAyahId(2, 13).toLong(),
                        endAyahId = QuranData.getAyahId(2, 13).toLong(),
                        lastUpdated = timestamp(1_700_000_002_000L)
                    ),
                    remoteID = "remote-created-note-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val remoteRecord = database.notesQueries.getNoteByRemoteId("remote-created-note-id").executeAsOne()
        assertEquals("remote-created-note-id", remoteRecord.remote_id)
        assertEquals(3, repository.getAllNotes().size)
        assertEquals(2, repository.fetchMutatedNotes(0).size)
    }

    @Test
    fun `remote created note without ACK persists separately when body and range do not exactly match`() = runTest {
        val note = repository.addNote(
            body = "local note",
            startSura = 2,
            startAyah = 13,
            endSura = 2,
            endAyah = 13,
            timestamp = timestamp(1_700_000_001_000L)
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote(
                        body = "remote note",
                        startAyahId = QuranData.getAyahId(2, 13).toLong(),
                        endAyahId = QuranData.getAyahId(2, 13).toLong(),
                        lastUpdated = timestamp(1_700_000_002_000L)
                    ),
                    remoteID = "remote-created-note-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val localRecord = database.notesQueries.getNoteByLocalId(note.localId.toLong()).executeAsOne()
        val remoteRecord = database.notesQueries.getNoteByRemoteId("remote-created-note-id").executeAsOne()
        assertEquals(null, localRecord.remote_id)
        assertEquals("remote-created-note-id", remoteRecord.remote_id)
        assertEquals(2, repository.getAllNotes().size)
        assertEquals(Mutation.CREATED, repository.fetchMutatedNotes(0).single().mutation)
    }

    private fun timestamp(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()
}

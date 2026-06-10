@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.NoteAyah
import com.quran.shared.syncengine.model.NoteRange
import com.quran.shared.syncengine.model.SyncNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NotesConflictResolverTest {

    @Test
    fun `resolve suppresses local create when replayed remote create matches exactly one pending note`() {
        val remoteMutation = RemoteModelMutation(
            model = SyncNote(
                id = "remote-1",
                body = "Note body",
                ranges = listOf(noteRange()),
                lastModified = Instant.fromEpochMilliseconds(2000)
            ),
            remoteID = "remote-1",
            mutation = Mutation.CREATED
        )
        val localMutation = LocalModelMutation(
            model = SyncNote(
                id = "local-1",
                body = "Note body",
                ranges = listOf(noteRange()),
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )

        val detection = NotesConflictDetector(
            remoteMutations = listOf(remoteMutation),
            localMutations = listOf(localMutation)
        ).getConflicts()
        val resolution = NotesConflictResolver(detection.conflicts).resolve()

        assertEquals(1, detection.conflicts.size)
        assertEquals(emptyList(), detection.nonConflictingLocalMutations)
        assertEquals(emptyList(), detection.nonConflictingRemoteMutations)
        assertEquals(listOf(remoteMutation), resolution.mutationsToPersist)
        assertEquals(emptyList(), resolution.mutationsToPush)
    }

    @Test
    fun `detect leaves ambiguous replayed remote note create separate from local creates`() {
        val remoteMutation = RemoteModelMutation(
            model = SyncNote(
                id = "remote-1",
                body = "Note body",
                ranges = listOf(noteRange()),
                lastModified = Instant.fromEpochMilliseconds(2000)
            ),
            remoteID = "remote-1",
            mutation = Mutation.CREATED
        )
        val localMutations = listOf(
            noteCreate("local-1"),
            noteCreate("local-2")
        )

        val detection = NotesConflictDetector(
            remoteMutations = listOf(remoteMutation),
            localMutations = localMutations
        ).getConflicts()

        assertEquals(0, detection.conflicts.size)
        assertEquals(listOf(remoteMutation), detection.nonConflictingRemoteMutations)
        assertEquals(localMutations, detection.nonConflictingLocalMutations)
    }

    @Test
    fun `resolve pushes local delete over replayed remote create echo`() {
        val remoteMutation = RemoteModelMutation(
            model = SyncNote(
                id = "remote-1",
                body = "Note body",
                ranges = listOf(noteRange()),
                lastModified = Instant.fromEpochMilliseconds(2000)
            ),
            remoteID = "remote-1",
            mutation = Mutation.CREATED
        )
        val localMutation = LocalModelMutation(
            model = SyncNote(
                id = "local-1",
                body = null,
                ranges = emptyList(),
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = "remote-1",
            localID = "local-1",
            mutation = Mutation.DELETED
        )

        val result = NotesConflictResolver(
            conflicts = listOf(
                ResourceConflict(
                    localMutations = listOf(localMutation),
                    remoteMutations = listOf(remoteMutation)
                )
            )
        ).resolve()

        assertEquals(emptyList(), result.mutationsToPersist)
        assertEquals(listOf(localMutation), result.mutationsToPush)
    }

    private fun noteRange(): NoteRange {
        return NoteRange(
            start = NoteAyah(sura = 1, ayah = 1),
            end = NoteAyah(sura = 1, ayah = 7)
        )
    }

    private fun noteCreate(localId: String): LocalModelMutation<SyncNote> {
        return LocalModelMutation(
            model = SyncNote(
                id = localId,
                body = "Note body",
                ranges = listOf(noteRange()),
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = null,
            localID = localId,
            mutation = Mutation.CREATED
        )
    }
}

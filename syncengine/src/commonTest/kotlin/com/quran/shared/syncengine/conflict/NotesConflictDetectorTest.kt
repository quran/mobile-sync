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

class NotesConflictDetectorTest {

    @Test
    fun `multi-range remote create does not semantically conflict with local single range create`() {
        val local = LocalModelMutation(
            model = note(
                id = "local-note-id",
                body = "same note",
                ranges = listOf(range(2, 13, 2, 13))
            ),
            remoteID = null,
            localID = "local-note-id",
            mutation = Mutation.CREATED
        )
        val remote = RemoteModelMutation(
            model = note(
                id = "remote-note-id",
                body = "same note",
                ranges = listOf(
                    range(2, 13, 2, 13),
                    range(2, 14, 2, 14)
                )
            ),
            remoteID = "remote-note-id",
            mutation = Mutation.CREATED
        )

        val result = NotesConflictDetector(
            remoteMutations = listOf(remote),
            localMutations = listOf(local)
        ).getConflicts()

        assertEquals(emptyList(), result.conflicts)
        assertEquals(listOf(remote), result.nonConflictingRemoteMutations)
        assertEquals(listOf(local), result.nonConflictingLocalMutations)
    }

    private fun note(id: String, body: String, ranges: List<NoteRange>): SyncNote =
        SyncNote(
            id = id,
            body = body,
            ranges = ranges,
            lastModified = Instant.fromEpochMilliseconds(1000L)
        )

    private fun range(startSura: Int, startAyah: Int, endSura: Int, endAyah: Int): NoteRange =
        NoteRange(
            start = NoteAyah(startSura, startAyah),
            end = NoteAyah(endSura, endAyah)
        )
}

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncReadingSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ReadingSessionsConflictDetectorTest {

    @Test
    fun `getConflicts with matching remote ids should detect conflict`() {
        val remoteMutations = listOf(
            RemoteModelMutation(
                model = SyncReadingSession(
                    id = "remote-1",
                    chapterNumber = 2,
                    verseNumber = 255,
                    lastModified = Instant.fromEpochMilliseconds(1000)
                ),
                remoteID = "remote-1",
                mutation = Mutation.CREATED
            )
        )
        val localMutations = listOf(
            LocalModelMutation(
                model = SyncReadingSession(
                    id = "local-1",
                    chapterNumber = 2,
                    verseNumber = 255,
                    lastModified = Instant.fromEpochMilliseconds(1001)
                ),
                remoteID = "remote-1",
                localID = "local-1",
                mutation = Mutation.MODIFIED
            )
        )

        val result = ReadingSessionsConflictDetector(remoteMutations, localMutations).getConflicts()

        assertEquals(1, result.conflicts.size)
        assertEquals(0, result.nonConflictingRemoteMutations.size)
        assertEquals(0, result.nonConflictingLocalMutations.size)
    }

    @Test
    fun `getConflicts with local unsynced mutation should leave it non-conflicting`() {
        val remoteMutations = emptyList<RemoteModelMutation<SyncReadingSession>>()
        val localMutations = listOf(
            LocalModelMutation(
                model = SyncReadingSession(
                    id = "local-1",
                    chapterNumber = 2,
                    verseNumber = 255,
                    lastModified = Instant.fromEpochMilliseconds(1001)
                ),
                remoteID = null,
                localID = "local-1",
                mutation = Mutation.CREATED
            )
        )

        val result = ReadingSessionsConflictDetector(remoteMutations, localMutations).getConflicts()

        assertEquals(0, result.conflicts.size)
        assertEquals(1, result.nonConflictingLocalMutations.size)
        assertEquals(0, result.nonConflictingRemoteMutations.size)
    }
}

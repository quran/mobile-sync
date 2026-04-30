@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncReadingSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ReadingSessionsConflictResolverTest {

    @Test
    fun `resolve prefers newest local mutation`() {
        val remoteMutation = RemoteModelMutation(
            model = SyncReadingSession(
                id = "remote-1",
                chapterNumber = 2,
                verseNumber = 255,
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = "remote-1",
            mutation = Mutation.CREATED
        )
        val localMutation = LocalModelMutation(
            model = SyncReadingSession(
                id = "local-1",
                chapterNumber = 2,
                verseNumber = 255,
                lastModified = Instant.fromEpochMilliseconds(2000)
            ),
            remoteID = "remote-1",
            localID = "local-1",
            mutation = Mutation.MODIFIED
        )

        val result = ReadingSessionsConflictResolver(
            listOf(
                ResourceConflict(
                    localMutations = listOf(localMutation),
                    remoteMutations = listOf(remoteMutation)
                )
            )
        ).resolve()

        assertEquals(0, result.mutationsToPersist.size)
        assertEquals(1, result.mutationsToPush.size)
        assertEquals(localMutation, result.mutationsToPush.single())
    }

    @Test
    fun `resolve prefers newest remote mutation on tie`() {
        val remoteMutation = RemoteModelMutation(
            model = SyncReadingSession(
                id = "remote-1",
                chapterNumber = 2,
                verseNumber = 255,
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = "remote-1",
            mutation = Mutation.CREATED
        )
        val localMutation = LocalModelMutation(
            model = SyncReadingSession(
                id = "local-1",
                chapterNumber = 2,
                verseNumber = 255,
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = "remote-1",
            localID = "local-1",
            mutation = Mutation.MODIFIED
        )

        val result = ReadingSessionsConflictResolver(
            listOf(
                ResourceConflict(
                    localMutations = listOf(localMutation),
                    remoteMutations = listOf(remoteMutation)
                )
            )
        ).resolve()

        assertEquals(1, result.mutationsToPersist.size)
        assertEquals(0, result.mutationsToPush.size)
        assertEquals(remoteMutation, result.mutationsToPersist.single())
    }
}

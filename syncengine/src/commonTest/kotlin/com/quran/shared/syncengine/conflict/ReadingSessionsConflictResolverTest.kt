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

    @Test
    fun `resolve pushes local delete over equal or newer replayed remote create echo`() {
        val equalTimestampRemoteMutation = RemoteModelMutation(
            model = SyncReadingSession(
                id = "remote-1",
                chapterNumber = 2,
                verseNumber = 255,
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = "remote-1",
            mutation = Mutation.CREATED
        )
        val equalTimestampLocalMutation = LocalModelMutation(
            model = SyncReadingSession(
                id = "local-1",
                chapterNumber = 2,
                verseNumber = 255,
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = "remote-1",
            localID = "local-1",
            mutation = Mutation.DELETED
        )
        val newerRemoteMutation = RemoteModelMutation(
            model = SyncReadingSession(
                id = "remote-2",
                chapterNumber = 3,
                verseNumber = 10,
                lastModified = Instant.fromEpochMilliseconds(3000)
            ),
            remoteID = "remote-2",
            mutation = Mutation.CREATED
        )
        val olderLocalMutation = LocalModelMutation(
            model = SyncReadingSession(
                id = "local-2",
                chapterNumber = 3,
                verseNumber = 10,
                lastModified = Instant.fromEpochMilliseconds(2000)
            ),
            remoteID = "remote-2",
            localID = "local-2",
            mutation = Mutation.DELETED
        )

        val result = ReadingSessionsConflictResolver(
            listOf(
                ResourceConflict(
                    localMutations = listOf(equalTimestampLocalMutation),
                    remoteMutations = listOf(equalTimestampRemoteMutation)
                ),
                ResourceConflict(
                    localMutations = listOf(olderLocalMutation),
                    remoteMutations = listOf(newerRemoteMutation)
                )
            )
        ).resolve()

        assertEquals(emptyList(), result.mutationsToPersist)
        assertEquals(listOf(equalTimestampLocalMutation, olderLocalMutation), result.mutationsToPush)
    }
}

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncReadingSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

class ReadingSessionsSyncAdapterTest {

    @Test
    fun `complete maps pushed mutations and uses local models`() = runTest {
        val localMutation = LocalModelMutation<SyncReadingSession>(
            model = SyncReadingSession(
                id = "local-1",
                chapterNumber = 2,
                verseNumber = 255,
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )

        val localDataFetcher = object : LocalDataFetcher<SyncReadingSession> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncReadingSession>> =
                listOf(localMutation)

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                remoteIDs.associateWith { true }

            override suspend fun fetchLocalModel(remoteId: String): SyncReadingSession? = null
        }

        var capturedRemote: List<RemoteModelMutation<SyncReadingSession>>? = null
        var capturedLocal: List<LocalModelMutation<SyncReadingSession>>? = null

        val resultNotifier = object : ResultNotifier<SyncReadingSession> {
            override suspend fun didSucceed(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<SyncReadingSession>>,
                processedLocalMutations: List<LocalModelMutation<SyncReadingSession>>
            ) {
                capturedRemote = newRemoteMutations
                capturedLocal = processedLocalMutations
            }

            override suspend fun didFail(message: String) {
            }
        }

        val localModificationDateFetcher = object : LocalModificationDateFetcher {
            override suspend fun localLastModificationDate(): Long? = 0L
        }

        val adapter = ReadingSessionsSyncAdapter(
            ReadingSessionsSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = resultNotifier,
                localModificationDateFetcher = localModificationDateFetcher
            )
        )

        val plan = adapter.buildPlan(0L, emptyList())
        assertEquals(1000L, plan.mutationsToPush().single().timestamp)

        val pushedMutations = listOf(
            SyncMutation(
                resource = "READING_SESSION",
                resourceId = "remote-abc",
                mutation = Mutation.CREATED,
                data = null,
                timestamp = null
            )
        )

        plan.complete(newToken = 10L, pushedMutations = pushedMutations)

        val remote = assertNotNull(capturedRemote)
        assertEquals(1, remote.size)
        assertEquals("remote-abc", remote[0].remoteID)
        assertEquals("local-1", remote[0].model.id)
    }
}

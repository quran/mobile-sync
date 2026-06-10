package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncReadingSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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

    @Test
    fun `remote reading session delete with null data is parsed with remote id`() = runTest {
        val capturedRemote = mutableListOf<RemoteModelMutation<SyncReadingSession>>()
        val adapter = adapterWithCapturedRemote(capturedRemote)

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "READING_SESSION",
                    resourceId = "remote-reading-session-id",
                    mutation = Mutation.DELETED,
                    data = null,
                    timestamp = 1000L
                )
            )
        )

        plan.complete(newToken = 10L, pushedMutations = emptyList())

        val remote = capturedRemote.single()
        assertEquals("remote-reading-session-id", remote.remoteID)
        assertEquals(Mutation.DELETED, remote.mutation)
        assertEquals("remote-reading-session-id", remote.model.id)
        assertEquals(0, remote.model.chapterNumber)
        assertEquals(0, remote.model.verseNumber)
    }

    @Test
    fun `remote reading session create with null data is still dropped`() = runTest {
        val capturedRemote = mutableListOf<RemoteModelMutation<SyncReadingSession>>()
        val adapter = adapterWithCapturedRemote(capturedRemote)

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "READING_SESSION",
                    resourceId = "remote-reading-session-id",
                    mutation = Mutation.CREATED,
                    data = null,
                    timestamp = 1000L
                )
            )
        )

        plan.complete(newToken = 10L, pushedMutations = emptyList())

        assertTrue(capturedRemote.isEmpty())
    }

    @Test
    fun `complete rejects extra pushed reading session mutations before notifying success`() = runTest {
        val localMutation = LocalModelMutation(
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
        var didSucceedCalled = false
        val adapter = adapterWithLocalMutations(
            localMutations = listOf(localMutation),
            onDidSucceed = { didSucceedCalled = true }
        )
        val plan = adapter.buildPlan(0L, emptyList())

        assertFailsWith<IllegalStateException> {
            plan.complete(
                newToken = 10L,
                pushedMutations = listOf(
                    SyncMutation(
                        resource = "READING_SESSION",
                        resourceId = "remote-1",
                        mutation = Mutation.CREATED,
                        data = null,
                        timestamp = null
                    ),
                    SyncMutation(
                        resource = "READING_SESSION",
                        resourceId = "remote-2",
                        mutation = Mutation.CREATED,
                        data = null,
                        timestamp = null
                    )
                )
            )
        }

        assertEquals(false, didSucceedCalled)
    }

    private fun adapterWithCapturedRemote(
        capturedRemote: MutableList<RemoteModelMutation<SyncReadingSession>>
    ): ReadingSessionsSyncAdapter {
        return adapterWithLocalMutations(
            localMutations = emptyList(),
            onDidSucceed = { newRemoteMutations ->
                capturedRemote += newRemoteMutations
            }
        )
    }

    private fun adapterWithLocalMutations(
        localMutations: List<LocalModelMutation<SyncReadingSession>>,
        onDidSucceed: (List<RemoteModelMutation<SyncReadingSession>>) -> Unit
    ): ReadingSessionsSyncAdapter {
        val localDataFetcher = object : LocalDataFetcher<SyncReadingSession> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncReadingSession>> =
                localMutations

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                remoteIDs.associateWith { false }

            override suspend fun fetchLocalModel(remoteId: String): SyncReadingSession? = null
        }

        val resultNotifier = object : ResultNotifier<SyncReadingSession> {
            override suspend fun didSucceed(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<SyncReadingSession>>,
                processedLocalMutations: List<LocalModelMutation<SyncReadingSession>>
            ) {
                onDidSucceed(newRemoteMutations)
            }

            override suspend fun didFail(message: String) {
            }
        }

        val localModificationDateFetcher = object : LocalModificationDateFetcher {
            override suspend fun localLastModificationDate(): Long? = 0L
        }

        return ReadingSessionsSyncAdapter(
            ReadingSessionsSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = resultNotifier,
                localModificationDateFetcher = localModificationDateFetcher
            )
        )
    }
}

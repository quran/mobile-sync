package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncReadingSession
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    @Test
    fun `newer pending local session is pushed over an older remote create at the same verse`() = runTest {
        val local = pendingLocalSession(sura = 1, ayah = 1, lastModified = "2026-10-12T20:19:36Z")
        val capturedRemote = mutableListOf<RemoteModelMutation<SyncReadingSession>>()
        val adapter = adapterWithLocalMutations(listOf(local)) { capturedRemote += it }

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(remoteSession(Mutation.CREATED, "remote-1-1", 1, 1, "2026-09-16T13:39:21Z"))
        )
        val pushed = plan.mutationsToPush().single()
        plan.complete(newToken = 10L, pushedMutations = listOf(pushed.copy(resourceId = "remote-1-1")))

        assertEquals(Mutation.CREATED, pushed.mutation)
        assertEquals(Instant.parse("2026-10-12T20:19:36Z").toEpochMilliseconds(), pushed.timestamp)
        val persisted = capturedRemote.single()
        assertEquals("remote-1-1", persisted.remoteID)
        assertEquals(Instant.parse("2026-10-12T20:19:36Z"), persisted.model.lastModified)
    }

    @Test
    fun `newer remote update at the same verse wins over an older pending local session`() = runTest {
        val local = pendingLocalSession(sura = 112, ayah = 1, lastModified = "2026-09-16T13:33:57Z")
        val remote = remoteSession(Mutation.MODIFIED, "remote-112-1", 112, 1, "2026-10-12T20:18:47Z")
        val capturedRemote = mutableListOf<RemoteModelMutation<SyncReadingSession>>()
        val adapter = adapterWithLocalMutations(listOf(local)) { capturedRemote += it }

        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = listOf(remote))
        assertEquals(emptyList(), plan.mutationsToPush())
        plan.complete(newToken = 10L, pushedMutations = emptyList())

        val persisted = capturedRemote.single()
        assertEquals("remote-112-1", persisted.remoteID)
        assertEquals(Mutation.MODIFIED, persisted.mutation)
        assertEquals(Instant.parse("2026-10-12T20:18:47Z"), persisted.model.lastModified)
    }

    private fun pendingLocalSession(sura: Int, ayah: Int, lastModified: String) = LocalModelMutation(
        model = SyncReadingSession(
            id = "local-$sura-$ayah",
            chapterNumber = sura,
            verseNumber = ayah,
            lastModified = Instant.parse(lastModified),
            createdAt = Instant.parse(lastModified)
        ),
        remoteID = null,
        localID = "local-$sura-$ayah",
        mutation = Mutation.CREATED
    )

    private fun remoteSession(
        mutation: Mutation,
        remoteId: String,
        sura: Int,
        ayah: Int,
        clientUpdatedAt: String
    ) = SyncMutation(
        resource = "READING_SESSION",
        resourceId = remoteId,
        mutation = mutation,
        data = buildJsonObject {
            put("chapterNumber", sura)
            put("verseNumber", ayah)
            put("clientCreatedAt", "2026-07-01T00:00:00Z")
            put("clientUpdatedAt", clientUpdatedAt)
        },
        timestamp = Instant.parse(clientUpdatedAt).toEpochMilliseconds()
    )

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

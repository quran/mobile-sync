@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.time.Instant

class BookmarksSyncAdapterTest {
    @Test
    fun `ayah bookmark is serialized without reading bookmark fields`() = runTest {
        val adapter = adapterWithLocalMutations(listOf(localAyah()))

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()
        val data = assertNotNull(mutation.data)

        assertEquals("BOOKMARK", mutation.resource)
        assertEquals("ayah", data["type"]?.jsonPrimitive?.content)
        assertEquals(4, data["key"]?.jsonPrimitive?.int)
        assertEquals(7, data["verseNumber"]?.jsonPrimitive?.int)
        assertEquals(null, data["isReading"])
    }

    @Test
    fun `remote page bookmark is ignored by ayah bookmark adapter`() = runTest {
        var persisted = listOf<RemoteModelMutation<SyncBookmark>>()
        val adapter = adapterWithLocalMutations(emptyList()) { persisted = it }

        adapter.buildPlan(
            0L,
            listOf(
                SyncMutation(
                    resource = "BOOKMARK",
                    resourceId = "remote-page",
                    mutation = Mutation.CREATED,
                    data = buildJsonObject { put("type", "page"); put("key", 42) },
                    timestamp = 1000
                )
            )
        ).complete(1L, emptyList())

        assertEquals(emptyList(), persisted)
    }

    @Test
    fun `complete maps pushed create to its local model`() = runTest {
        var captured = listOf<RemoteModelMutation<SyncBookmark>>()
        val plan = adapterWithLocalMutations(listOf(localAyah())) { captured = it }
            .buildPlan(0L, emptyList())

        plan.complete(
            5L,
            listOf(SyncMutation("BOOKMARK", "remote-123", Mutation.CREATED, null, null))
        )

        assertEquals("remote-123", captured.single().remoteID)
        assertEquals("local-ayah", (captured.single().model as SyncBookmark.AyahBookmark).id)
    }

    @Test
    fun `complete rejects pushed delete with mismatched remote id`() = runTest {
        val local = LocalModelMutation<SyncBookmark>(
            model = localAyah().model,
            remoteID = "remote-a",
            localID = "local-ayah",
            mutation = Mutation.DELETED
        )
        val plan = adapterWithLocalMutations(listOf(local)).buildPlan(0L, emptyList())

        assertFailsWith<IllegalStateException> {
            plan.complete(
                5L,
                listOf(SyncMutation("BOOKMARK", "remote-b", Mutation.DELETED, null, null))
            )
        }
    }

    private fun localAyah(): LocalModelMutation<SyncBookmark> = LocalModelMutation(
        model = SyncBookmark.AyahBookmark(
            id = "local-ayah",
            sura = 4,
            ayah = 7,
            lastModified = Instant.fromEpochMilliseconds(1000)
        ),
        remoteID = null,
        localID = "local-ayah",
        mutation = Mutation.CREATED
    )

    private fun adapterWithLocalMutations(
        localMutations: List<LocalModelMutation<SyncBookmark>>,
        onRemote: (List<RemoteModelMutation<SyncBookmark>>) -> Unit = {}
    ) = BookmarksSyncAdapter(
        BookmarksSynchronizationConfigurations(
            localDataFetcher = object : LocalDataFetcher<SyncBookmark> {
                override suspend fun fetchLocalMutations(lastModified: Long) = localMutations
                override suspend fun checkLocalExistence(remoteIDs: List<String>) = remoteIDs.associateWith { true }
                override suspend fun fetchLocalModel(remoteId: String): SyncBookmark? = null
            },
            resultNotifier = object : ResultNotifier<SyncBookmark> {
                override suspend fun didSucceed(
                    newToken: Long,
                    newRemoteMutations: List<RemoteModelMutation<SyncBookmark>>,
                    processedLocalMutations: List<LocalModelMutation<SyncBookmark>>
                ) = onRemote(newRemoteMutations)

                override suspend fun didFail(message: String) = fail(message)
            },
            localModificationDateFetcher = object : LocalModificationDateFetcher {
                override suspend fun localLastModificationDate(): Long = 0L
            }
        )
    )
}

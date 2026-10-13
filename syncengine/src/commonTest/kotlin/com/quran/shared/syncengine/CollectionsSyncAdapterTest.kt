package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CollectionsSyncAdapterTest {

    @Test
    fun `pushed collection data holds only the name and client timestamps the server accepts`() = runTest {
        val adapter = adapterPushing(
            collectionMutation("local-1", "system:highlights:pink", isSystem = true, mutation = Mutation.CREATED),
            collectionMutation("local-2", "Old Page Bookmarks", isSystem = false, mutation = Mutation.CREATED),
            collectionMutation("local-3", "Renamed", isSystem = false, mutation = Mutation.MODIFIED, remoteId = "remote-3")
        )

        val pushed = adapter.buildPlan(0L, emptyList()).mutationsToPush()

        assertEquals(3, pushed.size)
        val (creates, updates) = pushed.partition { it.mutation == Mutation.CREATED }
        creates.forEach { assertEquals(setOf("name", "clientCreatedAt", "clientUpdatedAt"), requireNotNull(it.data).keys) }
        updates.forEach { assertEquals(setOf("name", "clientUpdatedAt"), requireNotNull(it.data).keys) }
        assertEquals(
            listOf("system:highlights:pink", "Old Page Bookmarks", "Renamed"),
            pushed.map { (it.data?.get("name") as JsonPrimitive).content }
        )
    }

    @Test
    fun `pushed collection name is HTML-escaped for the server's sanitizer`() = runTest {
        val adapter = adapterPushing(
            collectionMutation("local-1", "Tom & Jerry <3", isSystem = false, mutation = Mutation.CREATED)
        )

        val pushed = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()

        assertEquals("Tom &amp; Jerry &lt;3", (pushed.data?.get("name") as JsonPrimitive).content)
    }

    @Test
    fun `remote collection name is unescaped once`() = runTest {
        val persisted = mutableListOf<RemoteModelMutation<SyncCollection>>()
        val adapter = adapter(emptyList(), onDidSucceed = { persisted += it })

        adapter.buildPlan(0L, listOf(remoteCollection("remote-1", "tom &amp; jerry &amp;lt;3")))
            .complete(newToken = 10L, pushedMutations = emptyList())

        assertEquals("tom & jerry &lt;3", persisted.single().model.name)
    }

    @Test
    fun `remote lowercase name matches a pending local collection with mixed case`() = runTest {
        val local = collectionMutation("local-1", "\uFEFF Study\u3000", isSystem = false, mutation = Mutation.CREATED)
        val persisted = mutableListOf<RemoteModelMutation<SyncCollection>>()
        val adapter = adapter(listOf(local), onDidSucceed = { persisted += it })

        val plan = adapter.buildPlan(0L, listOf(remoteCollection("remote-study", "study")))

        assertEquals(emptyList(), plan.mutationsToPush())
        plan.complete(newToken = 10L, pushedMutations = emptyList())
        assertEquals(listOf("remote-study"), persisted.map { it.remoteID })
    }

    private fun remoteCollection(remoteId: String, name: String) = SyncMutation(
        resource = "COLLECTION",
        resourceId = remoteId,
        mutation = Mutation.MODIFIED,
        data = buildJsonObject {
            put("name", name)
            put("clientCreatedAt", "1970-01-01T00:00:01Z")
            put("clientUpdatedAt", "1970-01-01T00:00:03Z")
        },
        timestamp = 3_000L
    )

    private fun collectionMutation(
        id: String,
        name: String,
        isSystem: Boolean,
        mutation: Mutation,
        remoteId: String? = null
    ) = LocalModelMutation(
        model = SyncCollection(
            id = id,
            name = name,
            lastModified = Instant.fromEpochMilliseconds(2_000),
            createdAt = Instant.fromEpochMilliseconds(1_000),
            isDefault = false,
            isSystem = isSystem
        ),
        remoteID = remoteId,
        localID = id,
        mutation = mutation
    )

    private fun adapterPushing(vararg mutations: LocalModelMutation<SyncCollection>) = adapter(mutations.toList())

    private fun adapter(
        localMutations: List<LocalModelMutation<SyncCollection>>,
        onDidSucceed: (List<RemoteModelMutation<SyncCollection>>) -> Unit = {}
    ) = CollectionsSyncAdapter(
        CollectionsSynchronizationConfigurations(
            localDataFetcher = object : LocalDataFetcher<SyncCollection> {
                override suspend fun fetchLocalMutations(lastModified: Long) = localMutations

                override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                    remoteIDs.associateWith { true }

                override suspend fun fetchLocalModel(remoteId: String): SyncCollection? = null
            },
            resultNotifier = object : ResultNotifier<SyncCollection> {
                override suspend fun didSucceed(
                    newToken: Long,
                    newRemoteMutations: List<RemoteModelMutation<SyncCollection>>,
                    processedLocalMutations: List<LocalModelMutation<SyncCollection>>
                ) {
                    onDidSucceed(newRemoteMutations)
                }

                override suspend fun didFail(message: String) = Unit
            },
            localModificationDateFetcher = object : LocalModificationDateFetcher {
                override suspend fun localLastModificationDate(): Long? = 0L
            }
        )
    )
}

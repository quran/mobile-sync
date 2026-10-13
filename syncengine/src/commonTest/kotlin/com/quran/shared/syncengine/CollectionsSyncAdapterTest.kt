package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
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
        localMutations: List<LocalModelMutation<SyncCollection>>
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
                ) = Unit

                override suspend fun didFail(message: String) = Unit
            },
            localModificationDateFetcher = object : LocalModificationDateFetcher {
                override suspend fun localLastModificationDate(): Long? = 0L
            }
        )
    )
}

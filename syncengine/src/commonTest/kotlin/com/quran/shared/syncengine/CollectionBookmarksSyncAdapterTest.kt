@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

class CollectionBookmarksSyncAdapterTest {

    @Test
    fun `default membership serializes with collection bookmark resource and default collection id`() = runTest {
        val localMutation: LocalModelMutation<SyncCollectionBookmark> = LocalModelMutation(
            model = SyncCollectionBookmark.PageBookmark(
                collectionId = "__default__",
                page = 42,
                lastModified = Instant.fromEpochMilliseconds(1_000),
                bookmarkId = "bookmark-42"
            ),
            remoteID = null,
            localID = "membership-1",
            mutation = Mutation.CREATED
        )
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(
                        remoteIDs: List<String>
                    ): Map<String, Boolean> = remoteIDs.associateWith { false }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) = Unit

                    override suspend fun didFail(message: String) {
                        fail("didFail called: $message")
                    }
                },
                localModificationDateFetcher = object : LocalModificationDateFetcher {
                    override suspend fun localLastModificationDate(): Long = 0
                }
            )
        )

        val mutation = adapter
            .buildPlan(lastModificationDate = 0, remoteMutations = emptyList())
            .mutationsToPush()
            .single()

        assertEquals("COLLECTION_BOOKMARK", mutation.resource)
        assertEquals("__default__", mutation.data?.get("collectionId")?.jsonPrimitive?.content)
        assertEquals("bookmark-42", mutation.data?.get("bookmarkId")?.jsonPrimitive?.content)
    }

    @Test
    fun `membership fallback identity includes collection while explicit identity is preserved`() = runTest {
        val localMutation: LocalModelMutation<SyncCollectionBookmark> = LocalModelMutation(
            model = SyncCollectionBookmark.PageBookmark(
                collectionId = "__default__",
                page = 42,
                lastModified = Instant.fromEpochMilliseconds(500),
                bookmarkId = "bookmark-42"
            ),
            remoteID = null,
            localID = "pending-default-membership",
            mutation = Mutation.CREATED
        )
        var persistedMutations = emptyList<RemoteModelMutation<SyncCollectionBookmark>>()
        var clearedMutations = emptyList<LocalModelMutation<SyncCollectionBookmark>>()
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(
                        remoteIDs: List<String>
                    ): Map<String, Boolean> = remoteIDs.associateWith { false }

                    override suspend fun fetchLocalModel(
                        remoteId: String
                    ): SyncCollectionBookmark = SyncCollectionBookmark.PageBookmark(
                        collectionId = "",
                        page = 42,
                        lastModified = Instant.fromEpochMilliseconds(500),
                        bookmarkId = remoteId
                    )
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
                        persistedMutations = newRemoteMutations
                        clearedMutations = processedLocalMutations
                    }

                    override suspend fun didFail(message: String) {
                        fail("didFail called: $message")
                    }
                },
                localModificationDateFetcher = object : LocalModificationDateFetcher {
                    override suspend fun localLastModificationDate(): Long = 0
                }
            )
        )
        val remoteMutations = listOf(
            collectionMembershipMutation("__default__", "bookmark-42"),
            collectionMembershipMutation("custom-collection", "bookmark-42"),
            collectionMembershipMutation(
                collectionId = "legacy-collection",
                bookmarkId = "bookmark-42",
                resourceId = "server-membership-id"
            )
        )

        val plan = adapter.buildPlan(lastModificationDate = 0, remoteMutations = remoteMutations)

        assertTrue(plan.mutationsToPush().isEmpty())
        plan.complete(newToken = 2_000, pushedMutations = emptyList())
        assertEquals(
            setOf(
                "__default__-bookmark-42",
                "custom-collection-bookmark-42",
                "server-membership-id"
            ),
            persistedMutations.map { it.remoteID }.toSet()
        )
        assertEquals(
            setOf("__default__", "custom-collection", "legacy-collection"),
            persistedMutations.map { it.model.collectionId }.toSet()
        )
        assertEquals(
            listOf("pending-default-membership"),
            clearedMutations.map { it.localID }
        )
    }

    private fun collectionMembershipMutation(
        collectionId: String,
        bookmarkId: String,
        resourceId: String? = null
    ): SyncMutation = SyncMutation(
        resource = "COLLECTION_BOOKMARK",
        resourceId = resourceId,
        mutation = Mutation.CREATED,
        data = buildJsonObject {
            put("collectionId", collectionId)
            put("bookmarkId", bookmarkId)
        },
        timestamp = 1_000
    )
}

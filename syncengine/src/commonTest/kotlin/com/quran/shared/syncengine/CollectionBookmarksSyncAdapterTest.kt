@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

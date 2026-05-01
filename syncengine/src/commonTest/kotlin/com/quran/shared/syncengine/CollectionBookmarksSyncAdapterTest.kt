@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.collectionBookmarkRemoteId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.time.Instant

class CollectionBookmarksSyncAdapterTest {

    @Test
    fun `mutations to push omit bookmarkId payload field`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = "remote-bookmark-1"
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )

        val localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncCollectionBookmark>> =
                listOf(localMutation)

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                remoteIDs.associateWith { true }

            override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
        }

        var capturedRemote: List<RemoteModelMutation<SyncCollectionBookmark>>? = null
        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null

        val resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
            override suspend fun didSucceed(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
            ) {
                capturedRemote = newRemoteMutations
                capturedLocal = processedLocalMutations
            }

            override suspend fun didFail(message: String) {
                fail("didFail called: $message")
            }
        }

        val localModificationDateFetcher = object : LocalModificationDateFetcher {
            override suspend fun localLastModificationDate(): Long? = 0L
        }

        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = resultNotifier,
                localModificationDateFetcher = localModificationDateFetcher
            )
        )

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = emptyList()
        )

        val pushedMutations = plan.mutationsToPush()
        assertEquals(1, pushedMutations.size)
        assertEquals("COLLECTION_BOOKMARK", pushedMutations.single().resource)
        assertNull(pushedMutations.single().resourceId)
        assertEquals("remote-collection-1", pushedMutations.single().data?.get("collectionId")?.jsonPrimitive?.content)
        assertNull(pushedMutations.single().data?.get("bookmarkId"))

        plan.complete(
            newToken = 5L,
            pushedMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = null,
                    mutation = Mutation.CREATED,
                    data = null,
                    timestamp = null
                )
            )
        )

        val remote = assertNotNull(capturedRemote)
        assertEquals(1, remote.size)
        assertEquals(collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-1"), remote.single().remoteID)

        val local = assertNotNull(capturedLocal)
        assertEquals(1, local.size)
        assertEquals("local-1", local.single().localID)
    }

    @Test
    fun `remote collection bookmark uses response resource id when present`() = runTest {
        val localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncCollectionBookmark>> =
                emptyList()

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                remoteIDs.associateWith { true }

            override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
        }

        var capturedRemote: List<RemoteModelMutation<SyncCollectionBookmark>>? = null

        val resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
            override suspend fun didSucceed(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
            ) {
                capturedRemote = newRemoteMutations
            }

            override suspend fun didFail(message: String) {
                fail("didFail called: $message")
            }
        }

        val localModificationDateFetcher = object : LocalModificationDateFetcher {
            override suspend fun localLastModificationDate(): Long? = 0L
        }

        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = resultNotifier,
                localModificationDateFetcher = localModificationDateFetcher
            )
        )

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = "remote-collection-2-remote-bookmark-2",
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("collectionId", "remote-collection-2")
                        put("type", "ayah")
                        put("key", 2)
                        put("verseNumber", 255)
                        put("mushaf", 4)
                    },
                    timestamp = 123L
                )
            )
        )

        plan.complete(newToken = 5L, pushedMutations = emptyList())

        val remote = assertNotNull(capturedRemote)
        assertEquals(1, remote.size)
        assertEquals("remote-collection-2-remote-bookmark-2", remote.single().remoteID)
    }

    @Test
    fun `remote collection bookmark can use same batch bookmark payload`() = runTest {
        val localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncCollectionBookmark>> =
                emptyList()

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                remoteIDs.associateWith { true }

            override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
        }

        var capturedRemote: List<RemoteModelMutation<SyncCollectionBookmark>>? = null

        val resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
            override suspend fun didSucceed(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
            ) {
                capturedRemote = newRemoteMutations
            }

            override suspend fun didFail(message: String) {
                fail("didFail called: $message")
            }
        }

        val localModificationDateFetcher = object : LocalModificationDateFetcher {
            override suspend fun localLastModificationDate(): Long? = 0L
        }

        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = resultNotifier,
                localModificationDateFetcher = localModificationDateFetcher
            )
        )

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "BOOKMARK",
                    resourceId = "remote-bookmark-3",
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("type", "ayah")
                        put("key", 36)
                        put("verseNumber", 58)
                        put("mushaf", 4)
                    },
                    timestamp = 100L
                ),
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = null,
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("collectionId", "remote-collection-3")
                        put("bookmarkId", "remote-bookmark-3")
                    },
                    timestamp = 101L
                )
            )
        )

        plan.complete(newToken = 5L, pushedMutations = emptyList())

        val remote = assertNotNull(capturedRemote)
        assertEquals(1, remote.size)
        val mutation = remote.single()
        assertEquals(
            collectionBookmarkRemoteId("remote-collection-3", "remote-bookmark-3"),
            mutation.remoteID
        )
        val model = mutation.model as SyncCollectionBookmark.AyahBookmark
        assertEquals(36, model.sura)
        assertEquals(58, model.ayah)
        assertEquals("remote-bookmark-3", model.bookmarkId)
    }
}

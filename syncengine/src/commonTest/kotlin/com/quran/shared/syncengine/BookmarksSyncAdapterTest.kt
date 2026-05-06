@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.time.Instant

class BookmarksSyncAdapterTest {

    @Test
    fun `page reading bookmark is serialized as page bookmark mutation`() = runTest {
        val localMutation = LocalModelMutation<SyncBookmark>(
            model = SyncBookmark.PageBookmark(
                id = "local-page-reading",
                page = 42,
                isReading = true,
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = null,
            localID = "local-page-reading",
            mutation = Mutation.CREATED
        )

        val adapter = BookmarksSyncAdapter(
            BookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncBookmark> {
                    override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncBookmark>> =
                        listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncBookmark>>
                    ) = Unit

                    override suspend fun didFail(message: String) {
                        fail("didFail called: $message")
                    }
                },
                localModificationDateFetcher = object : LocalModificationDateFetcher {
                    override suspend fun localLastModificationDate(): Long? = 0L
                }
            )
        )

        val mutation = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = emptyList()
        ).mutationsToPush().single()

        val data = assertNotNull(mutation.data)
        assertEquals("BOOKMARK", mutation.resource)
        assertEquals("page", data["type"]?.jsonPrimitive?.content)
        assertEquals(42, data["key"]?.jsonPrimitive?.int)
        assertEquals(true, data["isReading"]?.jsonPrimitive?.boolean)
        assertEquals(1, data["mushaf"]?.jsonPrimitive?.int)
    }

    @Test
    fun `complete maps pushed mutations by order and uses local models`() = runTest {
        val localMutation = LocalModelMutation<SyncBookmark>(
            model = SyncBookmark.AyahBookmark(
                id = "local-1",
                sura = 12,
                ayah = 1,
                isReading = true,
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )

        val localDataFetcher = object : LocalDataFetcher<SyncBookmark> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncBookmark>> =
                listOf(localMutation)

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                remoteIDs.associateWith { true }

            override suspend fun fetchLocalModel(remoteId: String): SyncBookmark? = null
        }

        var capturedRemote: List<RemoteModelMutation<SyncBookmark>>? = null
        var capturedLocal: List<LocalModelMutation<SyncBookmark>>? = null

        val resultNotifier = object : ResultNotifier<SyncBookmark> {
            override suspend fun didSucceed(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<SyncBookmark>>,
                processedLocalMutations: List<LocalModelMutation<SyncBookmark>>
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

        val adapter = BookmarksSyncAdapter(
            BookmarksSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = resultNotifier,
                localModificationDateFetcher = localModificationDateFetcher
            )
        )

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = emptyList()
        )

        val pushedMutations = listOf(
            SyncMutation(
                resource = "BOOKMARK",
                resourceId = "remote-123",
                mutation = Mutation.CREATED,
                data = null,
                timestamp = null
            )
        )

        plan.complete(newToken = 5L, pushedMutations = pushedMutations)

        val remote = assertNotNull(capturedRemote)
        assertEquals(1, remote.size)
        assertEquals("remote-123", remote[0].remoteID)
        assertEquals("local-1", (remote[0].model as SyncBookmark.AyahBookmark).id)

        val local = assertNotNull(capturedLocal)
        assertEquals(1, local.size)
        assertEquals("local-1", local[0].localID)
    }
}

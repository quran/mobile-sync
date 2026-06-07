@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertEquals(1000L, mutation.timestamp)
        assertEquals("page", data["type"]?.jsonPrimitive?.content)
        assertEquals(42, data["key"]?.jsonPrimitive?.int)
        assertEquals(true, data["isReading"]?.jsonPrimitive?.boolean)
        assertEquals(1, data["mushaf"]?.jsonPrimitive?.int)
    }

    @Test
    fun `local newer reading conflict is serialized as canonical remote update`() = runTest {
        val localMutation = LocalModelMutation<SyncBookmark>(
            model = SyncBookmark.AyahBookmark(
                id = "local-reading",
                sura = 4,
                ayah = 1,
                isReading = true,
                lastModified = Instant.fromEpochMilliseconds(2000)
            ),
            remoteID = null,
            localID = "local-reading",
            mutation = Mutation.CREATED
        )
        val remoteMutation = SyncMutation(
            resource = "BOOKMARK",
            resourceId = "remote-reading",
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("type", "ayah")
                put("bookmarkType", "ayah")
                put("key", 4)
                put("verseNumber", 1)
                put("mushaf", 1)
                put("isReading", false)
            },
            timestamp = 1000
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
            remoteMutations = listOf(remoteMutation)
        ).mutationsToPush().single()

        val data = assertNotNull(mutation.data)
        assertEquals("BOOKMARK", mutation.resource)
        assertEquals("remote-reading", mutation.resourceId)
        assertEquals(Mutation.MODIFIED, mutation.mutation)
        assertEquals(2000L, mutation.timestamp)
        assertEquals("ayah", data["type"]?.jsonPrimitive?.content)
        assertEquals(4, data["key"]?.jsonPrimitive?.int)
        assertEquals(1, data["verseNumber"]?.jsonPrimitive?.int)
        assertEquals(true, data["isReading"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `local newer reading conflict with stale remote id is serialized as fresh create`() = runTest {
        val localMutation = LocalModelMutation<SyncBookmark>(
            model = SyncBookmark.AyahBookmark(
                id = "local-reading",
                sura = 10,
                ayah = 4,
                isReading = true,
                lastModified = Instant.fromEpochMilliseconds(2000)
            ),
            remoteID = "remote-stale-reading",
            localID = "local-reading",
            mutation = Mutation.CREATED
        )
        val remoteMutation = SyncMutation(
            resource = "BOOKMARK",
            resourceId = "remote-stale-reading",
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("type", "ayah")
                put("bookmarkType", "ayah")
                put("key", 10)
                put("verseNumber", 3)
                put("mushaf", 1)
                put("isReading", false)
            },
            timestamp = 1000
        )
        var capturedRemote: List<RemoteModelMutation<SyncBookmark>>? = null
        var capturedLocal: List<LocalModelMutation<SyncBookmark>>? = null

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
                    ) {
                        capturedRemote = newRemoteMutations
                        capturedLocal = processedLocalMutations
                    }

                    override suspend fun didFail(message: String) {
                        fail("didFail called: $message")
                    }
                },
                localModificationDateFetcher = object : LocalModificationDateFetcher {
                    override suspend fun localLastModificationDate(): Long? = 0L
                }
            )
        )

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(remoteMutation)
        )
        val mutation = plan.mutationsToPush().single()

        val data = assertNotNull(mutation.data)
        assertEquals("BOOKMARK", mutation.resource)
        assertNull(mutation.resourceId)
        assertEquals(Mutation.CREATED, mutation.mutation)
        assertEquals(2000L, mutation.timestamp)
        assertEquals("ayah", data["type"]?.jsonPrimitive?.content)
        assertEquals(10, data["key"]?.jsonPrimitive?.int)
        assertEquals(4, data["verseNumber"]?.jsonPrimitive?.int)
        assertEquals(true, data["isReading"]?.jsonPrimitive?.boolean)

        plan.complete(
            newToken = 5L,
            pushedMutations = listOf(
                SyncMutation(
                    resource = "BOOKMARK",
                    resourceId = "remote-new-reading",
                    mutation = Mutation.CREATED,
                    data = null,
                    timestamp = null
                )
            )
        )

        assertEquals(
            listOf("remote-new-reading", "remote-stale-reading"),
            assertNotNull(capturedRemote).map { it.remoteID }
        )
        assertEquals(listOf("local-reading"), assertNotNull(capturedLocal).map { it.localID })
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

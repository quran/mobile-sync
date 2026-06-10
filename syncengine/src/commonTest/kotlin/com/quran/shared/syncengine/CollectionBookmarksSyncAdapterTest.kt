@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.collectionBookmarkRemoteId
import com.quran.shared.syncengine.network.MutationsResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.time.Instant

class CollectionBookmarksSyncAdapterTest {

    @Test
    fun `replayed remote create keeps same-link local delete pushable`() = runTest {
        val remoteId = collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-1")
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = "remote-bookmark-1"
            ),
            remoteID = remoteId,
            localID = "local-1",
            mutation = Mutation.DELETED,
            ack = LocalMutationAck(
                localID = "local-1",
                resource = LocalMutationResource.COLLECTION_BOOKMARK,
                facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
                observedPendingOp = Mutation.DELETED,
                observedPendingVersion = 7
            )
        )
        val remoteMutation = SyncMutation(
            resource = "COLLECTION_BOOKMARK",
            resourceId = remoteId,
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("collectionId", "remote-collection-1")
                put("type", "ayah")
                put("key", 2)
                put("verseNumber", 255)
                put("mushaf", 4)
            },
            timestamp = 2000L
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

        val preDependencyPlan = assertNotNull(
            adapter.buildPreDependencyDeletionPlan(
                lastModificationDate = 0L,
                remoteMutations = listOf(remoteMutation)
            )
        )
        val earlyPushedDelete = preDependencyPlan.mutationsToPush().single()
        assertEquals(remoteId, earlyPushedDelete.resourceId)
        assertEquals(Mutation.DELETED, earlyPushedDelete.mutation)

        preDependencyPlan.complete(newToken = 5L, pushedMutations = listOf(earlyPushedDelete))

        val earlyRemote = assertNotNull(capturedRemote)
        assertEquals(emptyList(), earlyRemote)

        val earlyLocal = assertNotNull(capturedLocal)
        assertEquals(1, earlyLocal.size)
        assertEquals("local-1", earlyLocal.single().localID)

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(remoteMutation)
        )
        val pushedDelete = plan.mutationsToPush().single()
        assertEquals(remoteId, pushedDelete.resourceId)
        assertEquals(Mutation.DELETED, pushedDelete.mutation)

        plan.complete(newToken = 5L, pushedMutations = listOf(pushedDelete))

        val remote = assertNotNull(capturedRemote)
        assertEquals(emptyList(), remote)

        val local = assertNotNull(capturedLocal)
        assertEquals(1, local.size)
        assertEquals("local-1", local.single().localID)
    }

    @Test
    fun `pre dependency delete prevents normal phase from replaying remote create echo`() = runTest {
        val remoteId = collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-1")
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = "remote-bookmark-1"
            ),
            remoteID = remoteId,
            localID = "local-1",
            mutation = Mutation.DELETED,
            ack = LocalMutationAck(
                localID = "local-1",
                resource = LocalMutationResource.COLLECTION_BOOKMARK,
                facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
                observedPendingOp = Mutation.DELETED,
                observedPendingVersion = 7
            )
        )
        val remoteCreateEcho = SyncMutation(
            resource = "COLLECTION_BOOKMARK",
            resourceId = remoteId,
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("collectionId", "remote-collection-1")
                put("bookmarkId", "remote-bookmark-1")
                put("type", "ayah")
                put("key", 2)
                put("verseNumber", 255)
                put("mushaf", 4)
            },
            timestamp = 2000L
        )
        var pendingDelete: LocalModelMutation<SyncCollectionBookmark>? = localMutation
        var linkActive = false
        val pushedMutations = mutableListOf<List<SyncMutation>>()

        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> =
                        listOfNotNull(pendingDelete)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
                        if (newRemoteMutations.any { it.remoteID == remoteId && it.mutation == Mutation.CREATED }) {
                            linkActive = true
                        }
                        if (processedLocalMutations.any { it.localID == "local-1" }) {
                            pendingDelete = null
                        }
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

        executeDependencyAwareSync(
            resourceAdapters = listOf(adapter),
            initialLastModificationDate = 0L,
            remoteResponse = MutationsResponse(
                lastModificationDate = 10L,
                mutations = listOf(remoteCreateEcho)
            ),
            pushMutations = { mutations, mutationToken, _ ->
                pushedMutations += mutations
                MutationsResponse(
                    lastModificationDate = mutationToken + 1,
                    mutations = mutations
                )
            }
        )

        assertEquals(false, linkActive)
        assertNull(pendingDelete)
        assertEquals(
            listOf(
                listOf(Mutation.DELETED),
                emptyList()
            ),
            pushedMutations.map { mutations -> mutations.map { it.mutation } }
        )
    }

    @Test
    fun `pre dependency delete filters null-id create echo by derived collection bookmark id only`() = runTest {
        val deletedRemoteId = collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-1")
        val unrelatedRemoteId = collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-2")
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = "remote-bookmark-1"
            ),
            remoteID = deletedRemoteId,
            localID = "local-1",
            mutation = Mutation.DELETED,
            ack = LocalMutationAck(
                localID = "local-1",
                resource = LocalMutationResource.COLLECTION_BOOKMARK,
                facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
                observedPendingOp = Mutation.DELETED,
                observedPendingVersion = 7
            )
        )
        val matchingEchoWithoutResourceId = SyncMutation(
            resource = "COLLECTION_BOOKMARK",
            resourceId = null,
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("collectionId", "remote-collection-1")
                put("bookmark_id", "remote-bookmark-1")
                put("type", "ayah")
                put("key", 2)
                put("verseNumber", 255)
                put("mushaf", 4)
            },
            timestamp = 2000L
        )
        val unrelatedCreate = SyncMutation(
            resource = "COLLECTION_BOOKMARK",
            resourceId = null,
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("collectionId", "remote-collection-1")
                put("bookmarkId", "remote-bookmark-2")
                put("type", "ayah")
                put("key", 2)
                put("verseNumber", 256)
                put("mushaf", 4)
            },
            timestamp = 2001L
        )
        var pendingDelete: LocalModelMutation<SyncCollectionBookmark>? = localMutation
        val persistedRemoteIds = mutableListOf<String>()
        val pushedMutations = mutableListOf<List<SyncMutation>>()

        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> =
                        listOfNotNull(pendingDelete)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
                        persistedRemoteIds += newRemoteMutations.map { it.remoteID }
                        if (processedLocalMutations.any { it.localID == "local-1" }) {
                            pendingDelete = null
                        }
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

        executeDependencyAwareSync(
            resourceAdapters = listOf(adapter),
            initialLastModificationDate = 0L,
            remoteResponse = MutationsResponse(
                lastModificationDate = 10L,
                mutations = listOf(matchingEchoWithoutResourceId, unrelatedCreate)
            ),
            pushMutations = { mutations, mutationToken, _ ->
                pushedMutations += mutations
                MutationsResponse(
                    lastModificationDate = mutationToken + 1,
                    mutations = mutations
                )
            }
        )

        assertEquals(listOf(unrelatedRemoteId), persistedRemoteIds)
        assertNull(pendingDelete)
        assertEquals(
            listOf(
                listOf(Mutation.DELETED),
                emptyList()
            ),
            pushedMutations.map { mutations -> mutations.map { it.mutation } }
        )
    }

    @Test
    fun `same-key remote delete conflicts with pending local create before existence filtering`() = runTest {
        val remoteId = collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-1")
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )
        val remoteMutation = SyncMutation(
            resource = "COLLECTION_BOOKMARK",
            resourceId = remoteId,
            mutation = Mutation.DELETED,
            data = buildJsonObject {
                put("collectionId", "remote-collection-1")
                put("bookmarkId", "remote-bookmark-1")
                put("type", "ayah")
                put("key", 2)
                put("verseNumber", 255)
                put("mushaf", 4)
            },
            timestamp = 2000L
        )

        val localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncCollectionBookmark>> =
                listOf(localMutation)

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                remoteIDs.associateWith { false }

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

        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = resultNotifier,
                localModificationDateFetcher = object : LocalModificationDateFetcher {
                    override suspend fun localLastModificationDate(): Long? = 0L
                }
            )
        )

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(remoteMutation)
        )
        assertEquals(emptyList(), plan.mutationsToPush())

        plan.complete(newToken = 5L, pushedMutations = emptyList())

        val remote = assertNotNull(capturedRemote).single()
        assertEquals(remoteId, remote.remoteID)
        assertEquals(Mutation.DELETED, remote.mutation)
        assertEquals("local-1", assertNotNull(capturedLocal).single().localID)
    }

    @Test
    fun `mutations to push include bookmarkId payload field`() = runTest {
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
        assertEquals(1000L, pushedMutations.single().timestamp)
        assertEquals("remote-collection-1", pushedMutations.single().data?.get("collectionId")?.jsonPrimitive?.content)
        assertEquals("remote-bookmark-1", pushedMutations.single().data?.get("bookmarkId")?.jsonPrimitive?.content)

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

        assertEquals(emptyList(), assertNotNull(capturedRemote))

        val local = assertNotNull(capturedLocal).single()
        assertEquals("local-1", local.localID)
        assertEquals(collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-1"), local.remoteID)
        val model = local.model as SyncCollectionBookmark.AyahBookmark
        assertEquals("remote-bookmark-1", model.bookmarkId)
    }

    @Test
    fun `pushed mutation response bookmarkId without location evidence only completes relation ACK`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
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

        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = resultNotifier,
                localModificationDateFetcher = object : LocalModificationDateFetcher {
                    override suspend fun localLastModificationDate(): Long? = 0L
                }
            )
        )

        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())
        val pushedMutations = plan.mutationsToPush()
        assertNull(pushedMutations.single().data?.get("bookmarkId"))

        plan.complete(
            newToken = 5L,
            pushedMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = null,
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("bookmarkId", "remote-bookmark-created")
                    },
                    timestamp = 1001L
                )
            )
        )

        assertEquals(emptyList(), assertNotNull(capturedRemote))

        val local = assertNotNull(capturedLocal).single()
        assertEquals("local-1", local.localID)
        assertEquals(collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-created"), local.remoteID)
        val model = local.model as SyncCollectionBookmark.AyahBookmark
        assertNull(model.bookmarkId)
    }

    @Test
    fun `default pushed mutation response bookmarkId with matching location evidence backfills parent id`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED,
            ack = LocalMutationAck(
                localID = "local-1",
                resource = LocalMutationResource.COLLECTION_BOOKMARK,
                facet = LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET,
                observedPendingOp = Mutation.CREATED,
                observedPendingVersion = 1
            )
        )

        val localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncCollectionBookmark>> =
                listOf(localMutation)

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                remoteIDs.associateWith { true }

            override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
        }

        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
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

        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())
        plan.complete(
            newToken = 5L,
            pushedMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = null,
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("bookmarkId", "remote-bookmark-created")
                        put("type", "ayah")
                        put("key", 2)
                        put("verseNumber", 255)
                    },
                    timestamp = 1001L
                )
            )
        )

        val local = assertNotNull(capturedLocal).single()
        assertEquals(collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-created"), local.remoteID)
        val model = local.model as SyncCollectionBookmark.AyahBookmark
        assertEquals("remote-bookmark-created", model.bookmarkId)
    }

    @Test
    fun `pushed mutation response bookmarkId with mismatched location evidence is rejected`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
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

        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
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

        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())
        assertFailsWith<IllegalStateException> {
            plan.complete(
                newToken = 5L,
                pushedMutations = listOf(
                    SyncMutation(
                        resource = "COLLECTION_BOOKMARK",
                        resourceId = null,
                        mutation = Mutation.CREATED,
                        data = buildJsonObject {
                            put("bookmarkId", "remote-bookmark-created")
                            put("type", "ayah")
                            put("key", 2)
                            put("verseNumber", 256)
                        },
                        timestamp = 1001L
                    )
                )
            )
        }

        assertNull(capturedLocal)
    }

    @Test
    fun `complete accepts create ACK with only matching composite resource id`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "thatCollection",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )
        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
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
        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

        plan.complete(
            newToken = 5L,
            pushedMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = collectionBookmarkRemoteId("thatCollection", "remoteBookmark"),
                    mutation = Mutation.CREATED,
                    data = null,
                    timestamp = null
                )
            )
        )

        val local = assertNotNull(capturedLocal).single()
        assertEquals(collectionBookmarkRemoteId("thatCollection", "remoteBookmark"), local.remoteID)
        val model = local.model as SyncCollectionBookmark.AyahBookmark
        assertNull(model.bookmarkId)
    }

    @Test
    fun `complete stores custom relation ACK composite id without backfilling parent bookmark id`() = runTest {
        val ack = LocalMutationAck(
            localID = "custom-link",
            resource = LocalMutationResource.COLLECTION_BOOKMARK,
            facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
            observedPendingOp = Mutation.CREATED,
            observedPendingVersion = 1
        )
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "custom-collection",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "custom-link",
            mutation = Mutation.CREATED,
            ack = ack
        )
        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
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
        val remoteId = collectionBookmarkRemoteId("custom-collection", "remoteBookmark")
        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

        plan.complete(
            newToken = 5L,
            pushedMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = remoteId,
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("collectionId", "custom-collection")
                        put("type", "ayah")
                        put("key", 2)
                        put("verseNumber", 255)
                    },
                    timestamp = null
                )
            )
        )

        val local = assertNotNull(capturedLocal).single()
        assertEquals(remoteId, local.remoteID)
        assertEquals(ack, local.ack)
        val model = local.model as SyncCollectionBookmark.AyahBookmark
        assertNull(model.bookmarkId)
    }

    @Test
    fun `complete stores default composite ACK payload without backfilling parent bookmark id`() = runTest {
        val ack = LocalMutationAck(
            localID = "local-1",
            resource = LocalMutationResource.COLLECTION_BOOKMARK,
            facet = LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET,
            observedPendingOp = Mutation.CREATED,
            observedPendingVersion = 1
        )
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "default-collection",
                sura = 20,
                ayah = 30,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED,
            ack = ack
        )
        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
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
        val remoteId = collectionBookmarkRemoteId("default-collection", "remoteBookmark")
        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

        plan.complete(
            newToken = 5L,
            pushedMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = remoteId,
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("collectionId", "default-collection")
                        put("type", "ayah")
                        put("key", 20)
                        put("verseNumber", 30)
                    },
                    timestamp = null
                )
            )
        )

        val local = assertNotNull(capturedLocal).single()
        assertEquals(remoteId, local.remoteID)
        val model = local.model as SyncCollectionBookmark.AyahBookmark
        assertNull(model.bookmarkId)
    }

    @Test
    fun `complete backfills default bookmark id from validated ACK payload without location`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "default-collection",
                sura = 20,
                ayah = 30,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "default-local-bookmark",
            mutation = Mutation.CREATED,
            ack = LocalMutationAck(
                localID = "default-local-bookmark",
                resource = LocalMutationResource.COLLECTION_BOOKMARK,
                facet = LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET,
                observedPendingOp = Mutation.CREATED,
                observedPendingVersion = 1
            )
        )
        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
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
        val remoteId = collectionBookmarkRemoteId("default-collection", "remoteBookmark")
        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

        plan.complete(
            newToken = 5L,
            pushedMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = remoteId,
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("collectionId", "default-collection")
                        put("bookmarkId", "remoteBookmark")
                    },
                    timestamp = null
                )
            )
        )

        val local = assertNotNull(capturedLocal).single()
        assertEquals(remoteId, local.remoteID)
        val model = local.model as SyncCollectionBookmark.AyahBookmark
        assertEquals("remoteBookmark", model.bookmarkId)
    }

    @Test
    fun `complete does not backfill custom bookmark id from ACK payload without location`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "custom-collection",
                sura = 20,
                ayah = 30,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "custom-link",
            mutation = Mutation.CREATED,
            ack = LocalMutationAck(
                localID = "custom-link",
                resource = LocalMutationResource.COLLECTION_BOOKMARK,
                facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
                observedPendingOp = Mutation.CREATED,
                observedPendingVersion = 1
            )
        )
        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
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
        val remoteId = collectionBookmarkRemoteId("custom-collection", "remoteBookmark")
        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

        plan.complete(
            newToken = 5L,
            pushedMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = remoteId,
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("collectionId", "custom-collection")
                        put("bookmarkId", "remoteBookmark")
                    },
                    timestamp = null
                )
            )
        )

        val local = assertNotNull(capturedLocal).single()
        assertEquals(remoteId, local.remoteID)
        val model = local.model as SyncCollectionBookmark.AyahBookmark
        assertNull(model.bookmarkId)
    }

    @Test
    fun `complete rejects composite ACK with payload location mismatch before notifying success`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "default-collection",
                sura = 20,
                ayah = 30,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )
        var didSucceedCalled = false
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
                        didSucceedCalled = true
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
        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

        assertFailsWith<IllegalStateException> {
            plan.complete(
                newToken = 5L,
                pushedMutations = listOf(
                    SyncMutation(
                        resource = "COLLECTION_BOOKMARK",
                        resourceId = collectionBookmarkRemoteId("default-collection", "remoteBookmark"),
                        mutation = Mutation.CREATED,
                        data = buildJsonObject {
                            put("collectionId", "default-collection")
                            put("type", "ayah")
                            put("key", 20)
                            put("verseNumber", 31)
                        },
                        timestamp = null
                    )
                )
            )
        }

        assertEquals(false, didSucceedCalled)
    }

    @Test
    fun `complete rejects create ACK with response collection id conflicting with request before notifying success`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "collection-a",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = "bookmark-a"
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )
        var didSucceedCalled = false
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
                        didSucceedCalled = true
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
        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

        assertFailsWith<IllegalStateException> {
            plan.complete(
                newToken = 5L,
                pushedMutations = listOf(
                    SyncMutation(
                        resource = "COLLECTION_BOOKMARK",
                        resourceId = null,
                        mutation = Mutation.CREATED,
                        data = buildJsonObject {
                            put("collectionId", "collection-d")
                            put("bookmarkId", "bookmark-a")
                        },
                        timestamp = null
                    )
                )
            )
        }

        assertEquals(false, didSucceedCalled)
    }

    @Test
    fun `complete rejects create ACK with response bookmark id conflicting with request before notifying success`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "collection-a",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = "bookmark-a"
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )
        var didSucceedCalled = false
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
                        didSucceedCalled = true
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
        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

        assertFailsWith<IllegalStateException> {
            plan.complete(
                newToken = 5L,
                pushedMutations = listOf(
                    SyncMutation(
                        resource = "COLLECTION_BOOKMARK",
                        resourceId = null,
                        mutation = Mutation.CREATED,
                        data = buildJsonObject {
                            put("collectionId", "collection-a")
                            put("bookmarkId", "bookmark-b")
                        },
                        timestamp = null
                    )
                )
            )
        }

        assertEquals(false, didSucceedCalled)
    }

    @Test
    fun `complete rejects create ACK with response bookmark id conflicting with composite before notifying success`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "collection-a",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )
        var didSucceedCalled = false
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
                        didSucceedCalled = true
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
        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

        assertFailsWith<IllegalStateException> {
            plan.complete(
                newToken = 5L,
                pushedMutations = listOf(
                    SyncMutation(
                        resource = "COLLECTION_BOOKMARK",
                        resourceId = collectionBookmarkRemoteId("collection-a", "bookmark-a"),
                        mutation = Mutation.CREATED,
                        data = buildJsonObject {
                            put("collectionId", "collection-a")
                            put("bookmarkId", "bookmark-b")
                        },
                        timestamp = null
                    )
                )
            )
        }

        assertEquals(false, didSucceedCalled)
    }

    @Test
    fun `complete rejects create ACK with mismatched composite collection id before notifying success`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "thatCollection",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "local-1",
            mutation = Mutation.CREATED
        )
        var didSucceedCalled = false
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
                        didSucceedCalled = true
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
        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

        assertFailsWith<IllegalStateException> {
            plan.complete(
                newToken = 5L,
                pushedMutations = listOf(
                    SyncMutation(
                        resource = "COLLECTION_BOOKMARK",
                        resourceId = collectionBookmarkRemoteId("otherCollection", "wrongBookmark"),
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
    fun `complete accepts create ACK resource id with independent relation evidence`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
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

        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
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

        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())
        plan.complete(
            newToken = 5L,
            pushedMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-created"),
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("bookmarkId", "remote-bookmark-created")
                    },
                    timestamp = 1001L
                )
            )
        )

        val local = assertNotNull(capturedLocal).single()
        assertEquals(collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-created"), local.remoteID)
        val model = local.model as SyncCollectionBookmark.AyahBookmark
        assertNull(model.bookmarkId)
    }

    @Test
    fun `custom collection bookmark create ACK with explicit bookmark id does not backfill parent bookmark`() = runTest {
        val cleared = completePushedCollectionBookmarkCreate(
            facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
            pushedResourceId = null,
            pushedData = collectionBookmarkAckData(bookmarkId = "remote-custom-bookmark")
        )

        assertEquals(collectionBookmarkRemoteId("remote-collection", "remote-custom-bookmark"), cleared.remoteID)
        assertNull((cleared.model as SyncCollectionBookmark.AyahBookmark).bookmarkId)
    }

    @Test
    fun `custom collection bookmark create ACK with composite id does not backfill parent bookmark`() = runTest {
        val remoteId = collectionBookmarkRemoteId("remote-collection", "remote-custom-bookmark")
        val cleared = completePushedCollectionBookmarkCreate(
            facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
            pushedResourceId = remoteId,
            pushedData = collectionBookmarkAckData(bookmarkId = null)
        )

        assertEquals(remoteId, cleared.remoteID)
        assertNull((cleared.model as SyncCollectionBookmark.AyahBookmark).bookmarkId)
    }

    @Test
    fun `default collection bookmark create ACK with explicit bookmark id backfills parent bookmark`() = runTest {
        val cleared = completePushedCollectionBookmarkCreate(
            facet = LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET,
            pushedResourceId = null,
            pushedData = collectionBookmarkAckData(bookmarkId = "remote-default-bookmark")
        )

        assertEquals(collectionBookmarkRemoteId("remote-collection", "remote-default-bookmark"), cleared.remoteID)
        assertEquals("remote-default-bookmark", (cleared.model as SyncCollectionBookmark.AyahBookmark).bookmarkId)
    }

    @Test
    fun `default collection bookmark create ACK with composite id does not backfill parent bookmark`() = runTest {
        val remoteId = collectionBookmarkRemoteId("remote-collection", "remote-default-bookmark")
        val cleared = completePushedCollectionBookmarkCreate(
            facet = LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET,
            pushedResourceId = remoteId,
            pushedData = collectionBookmarkAckData(bookmarkId = null)
        )

        assertEquals(remoteId, cleared.remoteID)
        assertNull((cleared.model as SyncCollectionBookmark.AyahBookmark).bookmarkId)
    }

    @Test
    fun `default collection bookmark create ACK with only composite id does not backfill parent bookmark`() = runTest {
        val remoteId = collectionBookmarkRemoteId("remote-collection", "remote-default-bookmark")
        val cleared = completePushedCollectionBookmarkCreate(
            facet = LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET,
            pushedResourceId = remoteId,
            pushedData = null
        )

        assertEquals(remoteId, cleared.remoteID)
        assertNull((cleared.model as SyncCollectionBookmark.AyahBookmark).bookmarkId)
    }

    @Test
    fun `complete rejects pushed delete with mismatched composite remote id before notifying success`() = runTest {
        val remoteId = collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-1")
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = "remote-bookmark-1"
            ),
            remoteID = remoteId,
            localID = "local-1",
            mutation = Mutation.DELETED
        )
        var didSucceedCalled = false
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
                        didSucceedCalled = true
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
        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

        assertFailsWith<IllegalStateException> {
            plan.complete(
                newToken = 5L,
                pushedMutations = listOf(
                    SyncMutation(
                        resource = "COLLECTION_BOOKMARK",
                        resourceId = collectionBookmarkRemoteId("remote-collection-1", "remote-bookmark-2"),
                        mutation = Mutation.DELETED,
                        data = null,
                        timestamp = null
                    )
                )
            )
        }

        assertEquals(false, didSucceedCalled)
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
    fun `remote delete with resource id and null data uses local model`() = runTest {
        val relationRemoteId = collectionBookmarkRemoteId("remote-collection-4", "remote-bookmark-4")
        val localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncCollectionBookmark>> =
                emptyList()

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                remoteIDs.associateWith { true }

            override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? =
                if (remoteId == relationRemoteId) {
                    SyncCollectionBookmark.AyahBookmark(
                        collectionId = "remote-collection-4",
                        sura = 4,
                        ayah = 12,
                        lastModified = Instant.fromEpochMilliseconds(900),
                        bookmarkId = "remote-bookmark-4"
                    )
                } else {
                    null
                }
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

        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localDataFetcher,
                resultNotifier = resultNotifier,
                localModificationDateFetcher = object : LocalModificationDateFetcher {
                    override suspend fun localLastModificationDate(): Long? = 0L
                }
            )
        )

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = relationRemoteId,
                    mutation = Mutation.DELETED,
                    data = null,
                    timestamp = 1000L
                )
            )
        )

        plan.complete(newToken = 5L, pushedMutations = emptyList())

        val remote = assertNotNull(capturedRemote).single()
        assertEquals(relationRemoteId, remote.remoteID)
        assertEquals(Mutation.DELETED, remote.mutation)
        val model = remote.model as SyncCollectionBookmark.AyahBookmark
        assertEquals(4, model.sura)
        assertEquals(12, model.ayah)
        assertEquals("remote-bookmark-4", model.bookmarkId)
        assertEquals(1000L, model.lastModified.toEpochMilliseconds())
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

    @Test
    fun `custom link create is not pushed or cleared when in-flight marker did not match row`() = runTest {
        val ack = LocalMutationAck(
            localID = "local-link",
            resource = LocalMutationResource.COLLECTION_BOOKMARK,
            facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
            observedPendingOp = Mutation.CREATED,
            observedPendingVersion = 1
        )
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection",
                sura = 10,
                ayah = 5,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "local-link",
            mutation = Mutation.CREATED,
            ack = ack
        )
        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null

        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { true }

                    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null

                    override suspend fun markLocalMutationsInFlight(
                        localMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ): List<LocalMutationAck> = emptyList()
                },
                resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                    ) {
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

        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())
        plan.markMutationsInFlight()
        assertEquals(emptyList(), plan.mutationsToPush())
        plan.complete(newToken = 5L, pushedMutations = emptyList())

        assertEquals(emptyList(), assertNotNull(capturedLocal))
    }

    @Test
    fun `replayed default collection bookmark create is cleared without a post when marker was not needed`() = runTest {
        val ack = LocalMutationAck(
            localID = "default-local-bookmark",
            resource = LocalMutationResource.COLLECTION_BOOKMARK,
            facet = LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET,
            observedPendingOp = Mutation.CREATED,
            observedPendingVersion = 1
        )
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "default-collection",
                sura = 10,
                ayah = 6,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "default-local-bookmark",
            mutation = Mutation.CREATED,
            ack = ack
        )
        val remoteId = collectionBookmarkRemoteId("default-collection", "remote-default-bookmark")
        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null

        val adapter = replayCreateAdapter(
            localMutation = localMutation,
            onDidSucceed = { _, processedLocalMutations ->
                capturedLocal = processedLocalMutations
            }
        )

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(collectionBookmarkCreateEcho("default-collection", "remote-default-bookmark", 10, 6))
        )
        plan.markMutationsInFlight()
        assertEquals(emptyList(), plan.mutationsToPush())
        plan.complete(newToken = 5L, pushedMutations = emptyList())

        val cleared = assertNotNull(capturedLocal).single()
        assertEquals("default-local-bookmark", cleared.localID)
        assertEquals(remoteId, cleared.remoteID)
        assertEquals(ack, cleared.ack)
        assertEquals("remote-default-bookmark", (cleared.model as SyncCollectionBookmark.AyahBookmark).bookmarkId)
    }

    @Test
    fun `replayed custom collection bookmark create is cleared without a post when marker was not needed`() = runTest {
        val ack = LocalMutationAck(
            localID = "custom-link",
            resource = LocalMutationResource.COLLECTION_BOOKMARK,
            facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
            observedPendingOp = Mutation.CREATED,
            observedPendingVersion = 1
        )
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection",
                sura = 10,
                ayah = 7,
                lastModified = Instant.fromEpochMilliseconds(1000),
                bookmarkId = null
            ),
            remoteID = null,
            localID = "custom-link",
            mutation = Mutation.CREATED,
            ack = ack
        )
        val remoteId = collectionBookmarkRemoteId("remote-collection", "remote-custom-bookmark")
        var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null

        val adapter = replayCreateAdapter(
            localMutation = localMutation,
            onDidSucceed = { _, processedLocalMutations ->
                capturedLocal = processedLocalMutations
            }
        )

        val plan = adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(collectionBookmarkCreateEcho("remote-collection", "remote-custom-bookmark", 10, 7))
        )
        plan.markMutationsInFlight()
        assertEquals(emptyList(), plan.mutationsToPush())
        plan.complete(newToken = 5L, pushedMutations = emptyList())

        val cleared = assertNotNull(capturedLocal).single()
        assertEquals("custom-link", cleared.localID)
        assertEquals(remoteId, cleared.remoteID)
        assertEquals(ack, cleared.ack)
        assertEquals("remote-custom-bookmark", (cleared.model as SyncCollectionBookmark.AyahBookmark).bookmarkId)
    }
}

private suspend fun completePushedCollectionBookmarkCreate(
    facet: String,
    pushedResourceId: String?,
    pushedData: JsonObject?
): LocalModelMutation<SyncCollectionBookmark> {
    val ack = LocalMutationAck(
        localID = "local-link",
        resource = LocalMutationResource.COLLECTION_BOOKMARK,
        facet = facet,
        observedPendingOp = Mutation.CREATED,
        observedPendingVersion = 1
    )
    val localMutation = LocalModelMutation<SyncCollectionBookmark>(
        model = SyncCollectionBookmark.AyahBookmark(
            collectionId = "remote-collection",
            sura = 10,
            ayah = 7,
            lastModified = Instant.fromEpochMilliseconds(1000),
            bookmarkId = null
        ),
        remoteID = null,
        localID = "local-link",
        mutation = Mutation.CREATED,
        ack = ack
    )
    var capturedLocal: List<LocalModelMutation<SyncCollectionBookmark>>? = null
    val adapter = CollectionBookmarksSyncAdapter(
        CollectionBookmarksSynchronizationConfigurations(
            localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                override suspend fun fetchLocalMutations(
                    lastModified: Long
                ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                    remoteIDs.associateWith { true }

                override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null
            },
            resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                override suspend fun didSucceed(
                    newToken: Long,
                    newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                    processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                ) {
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
    val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = emptyList())

    plan.complete(
        newToken = 5L,
        pushedMutations = listOf(
            SyncMutation(
                resource = "COLLECTION_BOOKMARK",
                resourceId = pushedResourceId,
                mutation = Mutation.CREATED,
                data = pushedData,
                timestamp = null
            )
        )
    )

    return assertNotNull(capturedLocal).single()
}

private fun collectionBookmarkAckData(bookmarkId: String?): JsonObject =
    buildJsonObject {
        put("collectionId", "remote-collection")
        bookmarkId?.let { put("bookmarkId", it) }
        put("type", "ayah")
        put("key", 10)
        put("verseNumber", 7)
        put("mushaf", 4)
    }

private fun replayCreateAdapter(
    localMutation: LocalModelMutation<SyncCollectionBookmark>,
    onDidSucceed: (
        List<RemoteModelMutation<SyncCollectionBookmark>>,
        List<LocalModelMutation<SyncCollectionBookmark>>
    ) -> Unit
): CollectionBookmarksSyncAdapter =
    CollectionBookmarksSyncAdapter(
        CollectionBookmarksSynchronizationConfigurations(
            localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                override suspend fun fetchLocalMutations(
                    lastModified: Long
                ): List<LocalModelMutation<SyncCollectionBookmark>> = listOf(localMutation)

                override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                    remoteIDs.associateWith { true }

                override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null

                override suspend fun markLocalMutationsInFlight(
                    localMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                ): List<LocalMutationAck> {
                    assertEquals(emptyList(), localMutations)
                    return emptyList()
                }
            },
            resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                override suspend fun didSucceed(
                    newToken: Long,
                    newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                    processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                ) {
                    onDidSucceed(newRemoteMutations, processedLocalMutations)
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

private fun collectionBookmarkCreateEcho(
    collectionId: String,
    bookmarkId: String,
    sura: Int,
    ayah: Int
): SyncMutation =
    SyncMutation(
        resource = "COLLECTION_BOOKMARK",
        resourceId = null,
        mutation = Mutation.CREATED,
        data = buildJsonObject {
            put("collectionId", collectionId)
            put("bookmarkId", bookmarkId)
            put("type", "ayah")
            put("key", sura)
            put("verseNumber", ayah)
            put("mushaf", 4)
        },
        timestamp = 2000L
    )

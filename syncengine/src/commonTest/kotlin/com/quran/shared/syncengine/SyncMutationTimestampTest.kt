@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.mutations.LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.NoteAyah
import com.quran.shared.syncengine.model.NoteRange
import com.quran.shared.syncengine.model.SyncCollection
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.SyncNote
import com.quran.shared.syncengine.model.SyncReadingSession
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Instant

class SyncMutationTimestampTest {

    @Test
    fun `bookmark create payload serializes client timestamps`() = runTest {
        val localMutation = LocalModelMutation<SyncBookmark>(
            model = SyncBookmark.AyahBookmark(
                id = "local-bookmark",
                sura = 2,
                ayah = 255,
                isReading = false,
                createdAt = Instant.fromEpochMilliseconds(1_000),
                lastModified = Instant.fromEpochMilliseconds(2_345)
            ),
            remoteID = null,
            localID = "local-bookmark",
            mutation = Mutation.CREATED
        )

        val adapter = BookmarksSyncAdapter(
            BookmarksSynchronizationConfigurations(
                localDataFetcher = localFetcher(listOf(localMutation)),
                resultNotifier = noopNotifier(),
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()

        assertClientTimestamps(assertNotNull(mutation.data), createdAt = 1_000, updatedAt = 2_345)
    }

    @Test
    fun `collection mutations carry local model timestamp`() = runTest {
        val localMutation = LocalModelMutation<SyncCollection>(
            model = SyncCollection(
                id = "local-collection",
                name = "Favorites",
                lastModified = Instant.fromEpochMilliseconds(12_345)
            ),
            remoteID = null,
            localID = "local-collection",
            mutation = Mutation.CREATED
        )

        val adapter = CollectionsSyncAdapter(
            CollectionsSynchronizationConfigurations(
                localDataFetcher = localFetcher(listOf(localMutation)),
                resultNotifier = noopNotifier(),
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()

        assertEquals(12_345L, mutation.timestamp)
    }

    @Test
    fun `collection update payload serializes client timestamps`() = runTest {
        val localMutation = LocalModelMutation<SyncCollection>(
            model = SyncCollection(
                id = "local-collection",
                name = "Favorites",
                createdAt = Instant.fromEpochMilliseconds(1_000),
                lastModified = Instant.fromEpochMilliseconds(2_345)
            ),
            remoteID = "remote-collection",
            localID = "local-collection",
            mutation = Mutation.MODIFIED
        )

        val adapter = CollectionsSyncAdapter(
            CollectionsSynchronizationConfigurations(
                localDataFetcher = localFetcher(listOf(localMutation)),
                resultNotifier = noopNotifier(),
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()

        assertClientTimestamps(assertNotNull(mutation.data), createdAt = 1_000, updatedAt = 2_345)
    }

    @Test
    fun `collection bookmark create payload serializes client timestamps`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection",
                sura = 2,
                ayah = 255,
                bookmarkId = "remote-bookmark",
                createdAt = Instant.fromEpochMilliseconds(1_000),
                lastModified = Instant.fromEpochMilliseconds(2_345)
            ),
            remoteID = null,
            localID = "local-collection-bookmark",
            mutation = Mutation.CREATED
        )

        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localFetcher(listOf(localMutation)),
                resultNotifier = noopNotifier(),
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()

        assertClientTimestamps(assertNotNull(mutation.data), createdAt = 1_000, updatedAt = 2_345)
    }

    @Test
    fun `collection bookmark delete payload omits client timestamps`() = runTest {
        val localMutation = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection",
                sura = 2,
                ayah = 255,
                bookmarkId = "remote-bookmark",
                createdAt = Instant.fromEpochMilliseconds(1_000),
                lastModified = Instant.fromEpochMilliseconds(2_345)
            ),
            remoteID = "remote-collection-remote-bookmark",
            localID = "local-collection-bookmark",
            mutation = Mutation.DELETED,
            ack = LocalMutationAck(
                localID = "local-collection-bookmark",
                resource = LocalMutationResource.COLLECTION_BOOKMARK,
                facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
                observedPendingOp = Mutation.DELETED,
                observedPendingVersion = 1
            )
        )

        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localFetcher(listOf(localMutation)),
                resultNotifier = noopNotifier(),
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()
        val data = assertNotNull(mutation.data)

        assertEquals("remote-collection", data["collectionId"]?.jsonPrimitive?.content)
        assertNull(data["clientCreatedAt"])
        assertNull(data["clientUpdatedAt"])
    }

    @Test
    fun `inbound bookmark create parses client timestamps`() = runTest {
        var capturedRemote = emptyList<RemoteModelMutation<SyncBookmark>>()
        val adapter = BookmarksSyncAdapter(
            BookmarksSynchronizationConfigurations(
                localDataFetcher = localFetcher(emptyList()),
                resultNotifier = capturingNotifier { capturedRemote = it },
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "BOOKMARK",
                    resourceId = "remote-bookmark",
                    mutation = Mutation.CREATED,
                    data = clientTimestampData(createdAt = 1_000, updatedAt = 2_345) {
                        put("type", "ayah")
                        put("key", 2)
                        put("verseNumber", 255)
                        put("isReading", false)
                    },
                    timestamp = 9_999
                )
            )
        ).complete(newToken = 10L, pushedMutations = emptyList())

        val model = capturedRemote.single().model as SyncBookmark.AyahBookmark
        assertEquals(instant(1_000), model.createdAt)
        assertEquals(instant(2_345), model.lastModified)
    }

    @Test
    fun `inbound collection update parses client timestamps`() = runTest {
        var capturedRemote = emptyList<RemoteModelMutation<SyncCollection>>()
        val adapter = CollectionsSyncAdapter(
            CollectionsSynchronizationConfigurations(
                localDataFetcher = localFetcher(emptyList()),
                resultNotifier = capturingNotifier { capturedRemote = it },
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION",
                    resourceId = "remote-collection",
                    mutation = Mutation.MODIFIED,
                    data = clientTimestampData(createdAt = 1_000, updatedAt = 2_345) {
                        put("name", "Favorites")
                    },
                    timestamp = 9_999
                )
            )
        ).complete(newToken = 10L, pushedMutations = emptyList())

        val model = capturedRemote.single().model
        assertEquals(instant(1_000), model.createdAt)
        assertEquals(instant(2_345), model.lastModified)
    }

    @Test
    fun `inbound collection timestamps fall back to server timestamp when client fields are invalid or absent`() = runTest {
        var capturedRemote = emptyList<RemoteModelMutation<SyncCollection>>()
        val adapter = CollectionsSyncAdapter(
            CollectionsSynchronizationConfigurations(
                localDataFetcher = localFetcher(emptyList()),
                resultNotifier = capturingNotifier { capturedRemote = it },
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION",
                    resourceId = "remote-collection",
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("name", "Favorites")
                        put("clientCreatedAt", "not-a-date")
                    },
                    timestamp = 9_999
                )
            )
        ).complete(newToken = 10L, pushedMutations = emptyList())

        val model = capturedRemote.single().model
        assertEquals(instant(9_999), model.createdAt)
        assertEquals(instant(9_999), model.lastModified)
    }

    @Test
    fun `inbound collection timestamps fall back to server timestamp when client fields are non primitive`() = runTest {
        var capturedRemote = emptyList<RemoteModelMutation<SyncCollection>>()
        val adapter = CollectionsSyncAdapter(
            CollectionsSynchronizationConfigurations(
                localDataFetcher = localFetcher(emptyList()),
                resultNotifier = capturingNotifier { capturedRemote = it },
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION",
                    resourceId = "remote-collection",
                    mutation = Mutation.CREATED,
                    data = buildJsonObject {
                        put("name", "Favorites")
                        put("clientCreatedAt", buildJsonObject {
                            put("value", instant(1_000).toString())
                        })
                        put("clientUpdatedAt", buildJsonArray {
                            add(instant(2_345).toString())
                        })
                    },
                    timestamp = 9_999
                )
            )
        ).complete(newToken = 10L, pushedMutations = emptyList())

        val model = capturedRemote.single().model
        assertEquals(instant(9_999), model.createdAt)
        assertEquals(instant(9_999), model.lastModified)
    }

    @Test
    fun `inbound collection delete ignores client timestamps`() = runTest {
        var capturedRemote = emptyList<RemoteModelMutation<SyncCollection>>()
        val adapter = CollectionsSyncAdapter(
            CollectionsSynchronizationConfigurations(
                localDataFetcher = localFetcher(emptyList()),
                resultNotifier = capturingNotifier { capturedRemote = it },
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION",
                    resourceId = "remote-collection",
                    mutation = Mutation.DELETED,
                    data = clientTimestampData(createdAt = 1_000, updatedAt = 2_345) {
                        put("name", "Favorites")
                    },
                    timestamp = 9_999
                )
            )
        ).complete(newToken = 10L, pushedMutations = emptyList())

        val model = capturedRemote.single().model
        assertNull(model.createdAt)
        assertEquals(instant(9_999), model.lastModified)
    }

    @Test
    fun `inbound collection bookmark create parses client timestamps`() = runTest {
        var capturedRemote = emptyList<RemoteModelMutation<SyncCollectionBookmark>>()
        val adapter = CollectionBookmarksSyncAdapter(
            CollectionBookmarksSynchronizationConfigurations(
                localDataFetcher = localFetcher(emptyList()),
                resultNotifier = capturingNotifier { capturedRemote = it },
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = "remote-collection-remote-bookmark",
                    mutation = Mutation.CREATED,
                    data = clientTimestampData(createdAt = 1_000, updatedAt = 2_345) {
                        put("collectionId", "remote-collection")
                        put("bookmarkId", "remote-bookmark")
                        put("type", "ayah")
                        put("key", 2)
                        put("verseNumber", 255)
                    },
                    timestamp = 9_999
                )
            )
        ).complete(newToken = 10L, pushedMutations = emptyList())

        val model = capturedRemote.single().model as SyncCollectionBookmark.AyahBookmark
        assertEquals(instant(1_000), model.createdAt)
        assertEquals(instant(2_345), model.lastModified)
    }

    @Test
    fun `inbound note create parses client timestamps`() = runTest {
        var capturedRemote = emptyList<RemoteModelMutation<SyncNote>>()
        val adapter = NotesSyncAdapter(
            NotesSynchronizationConfigurations(
                localDataFetcher = localFetcher(emptyList()),
                resultNotifier = capturingNotifier { capturedRemote = it },
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "NOTE",
                    resourceId = "remote-note",
                    mutation = Mutation.CREATED,
                    data = clientTimestampData(createdAt = 1_000, updatedAt = 2_345) {
                        put("body", "Note")
                        put("ranges", buildJsonArray {
                            add("2:255-2:255")
                        })
                    },
                    timestamp = 9_999
                )
            )
        ).complete(newToken = 10L, pushedMutations = emptyList())

        val model = capturedRemote.single().model
        assertEquals(instant(1_000), model.createdAt)
        assertEquals(instant(2_345), model.lastModified)
    }

    @Test
    fun `inbound reading session update parses client timestamps`() = runTest {
        var capturedRemote = emptyList<RemoteModelMutation<SyncReadingSession>>()
        val adapter = ReadingSessionsSyncAdapter(
            ReadingSessionsSynchronizationConfigurations(
                localDataFetcher = localFetcher(emptyList()),
                resultNotifier = capturingNotifier { capturedRemote = it },
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        adapter.buildPlan(
            lastModificationDate = 0L,
            remoteMutations = listOf(
                SyncMutation(
                    resource = "READING_SESSION",
                    resourceId = "remote-reading-session",
                    mutation = Mutation.MODIFIED,
                    data = clientTimestampData(createdAt = 1_000, updatedAt = 2_345) {
                        put("chapterNumber", 2)
                        put("verseNumber", 255)
                    },
                    timestamp = 9_999
                )
            )
        ).complete(newToken = 10L, pushedMutations = emptyList())

        val model = capturedRemote.single().model
        assertEquals(instant(1_000), model.createdAt)
        assertEquals(instant(2_345), model.lastModified)
    }

    @Test
    fun `note mutations carry local model timestamp`() = runTest {
        val localMutation = LocalModelMutation(
            model = SyncNote(
                id = "local-note",
                body = "Note",
                ranges = listOf(
                    NoteRange(
                        start = NoteAyah(sura = 2, ayah = 255),
                        end = NoteAyah(sura = 2, ayah = 255)
                    )
                ),
                lastModified = Instant.fromEpochMilliseconds(54_321)
            ),
            remoteID = null,
            localID = "local-note",
            mutation = Mutation.CREATED
        )

        val adapter = NotesSyncAdapter(
            NotesSynchronizationConfigurations(
                localDataFetcher = localFetcher(listOf(localMutation)),
                resultNotifier = noopNotifier(),
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()

        assertEquals(54_321L, mutation.timestamp)
    }

    @Test
    fun `note create payload serializes client timestamps`() = runTest {
        val localMutation = LocalModelMutation(
            model = SyncNote(
                id = "local-note",
                body = "Note",
                ranges = listOf(
                    NoteRange(
                        start = NoteAyah(sura = 2, ayah = 255),
                        end = NoteAyah(sura = 2, ayah = 255)
                    )
                ),
                createdAt = Instant.fromEpochMilliseconds(1_000),
                lastModified = Instant.fromEpochMilliseconds(2_345)
            ),
            remoteID = null,
            localID = "local-note",
            mutation = Mutation.CREATED
        )

        val adapter = NotesSyncAdapter(
            NotesSynchronizationConfigurations(
                localDataFetcher = localFetcher(listOf(localMutation)),
                resultNotifier = noopNotifier(),
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()

        assertClientTimestamps(assertNotNull(mutation.data), createdAt = 1_000, updatedAt = 2_345)
    }

    @Test
    fun `reading session update payload serializes client timestamps`() = runTest {
        val localMutation = LocalModelMutation(
            model = SyncReadingSession(
                id = "local-reading-session",
                chapterNumber = 2,
                verseNumber = 255,
                createdAt = Instant.fromEpochMilliseconds(1_000),
                lastModified = Instant.fromEpochMilliseconds(2_345)
            ),
            remoteID = "remote-reading-session",
            localID = "local-reading-session",
            mutation = Mutation.MODIFIED
        )

        val adapter = ReadingSessionsSyncAdapter(
            ReadingSessionsSynchronizationConfigurations(
                localDataFetcher = localFetcher(listOf(localMutation)),
                resultNotifier = noopNotifier(),
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()

        assertClientTimestamps(assertNotNull(mutation.data), createdAt = 1_000, updatedAt = 2_345)
    }

    private fun assertClientTimestamps(data: JsonObject, createdAt: Long, updatedAt: Long) {
        assertEquals(
            instant(createdAt).toString(),
            data["clientCreatedAt"]?.jsonPrimitive?.content
        )
        assertEquals(
            instant(updatedAt).toString(),
            data["clientUpdatedAt"]?.jsonPrimitive?.content
        )
    }

    private fun clientTimestampData(
        createdAt: Long,
        updatedAt: Long,
        content: JsonObjectBuilder.() -> Unit
    ): JsonObject {
        return buildJsonObject {
            content()
            put("clientCreatedAt", instant(createdAt).toString())
            put("clientUpdatedAt", instant(updatedAt).toString())
        }
    }

    private fun instant(epochMilliseconds: Long): Instant =
        Instant.fromEpochMilliseconds(epochMilliseconds)

    private fun <Model> localFetcher(
        localMutations: List<LocalModelMutation<Model>>
    ): LocalDataFetcher<Model> {
        return object : LocalDataFetcher<Model> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<Model>> {
                return localMutations
            }

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
                return remoteIDs.associateWith { true }
            }

            override suspend fun fetchLocalModel(remoteId: String): Model? = null
        }
    }

    private fun <Model> noopNotifier(): ResultNotifier<Model> {
        return object : ResultNotifier<Model> {
            override suspend fun didSucceed(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<Model>>,
                processedLocalMutations: List<LocalModelMutation<Model>>
            ) = Unit

            override suspend fun didFail(message: String) {
                fail("didFail called: $message")
            }
        }
    }

    private fun <Model> capturingNotifier(
        captureRemoteMutations: (List<RemoteModelMutation<Model>>) -> Unit
    ): ResultNotifier<Model> {
        return object : ResultNotifier<Model> {
            override suspend fun didSucceed(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<Model>>,
                processedLocalMutations: List<LocalModelMutation<Model>>
            ) {
                captureRemoteMutations(newRemoteMutations)
            }

            override suspend fun didFail(message: String) {
                fail("didFail called: $message")
            }
        }
    }

    private fun zeroModificationDateFetcher(): LocalModificationDateFetcher {
        return object : LocalModificationDateFetcher {
            override suspend fun localLastModificationDate(): Long? = 0L
        }
    }
}

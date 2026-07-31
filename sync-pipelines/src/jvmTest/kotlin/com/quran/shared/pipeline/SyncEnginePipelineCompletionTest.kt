package com.quran.shared.pipeline

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.Collection
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsSynchronizationRepository
import com.quran.shared.syncengine.AuthenticationDataFetcher
import com.quran.shared.syncengine.LocalModificationDateFetcher
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncEnginePipelineCompletionTest {

    @Test
    fun `first protocol replay keeps and pushes pending local mutations`() = runBlocking {
        ReplaySyncServer().use { server ->
            val settings = PropertiesSettings(Properties())
            settings["com.quran.sync.last_modified_date"] = 900L
            val dateFetcher = SettingsLocalModificationDateFetcher(settings)
            val bookmarkRepository = PendingBookmarksRepository()
            val completion = CompletableDeferred<Unit>()
            val pipeline = SyncEnginePipeline(
                bookmarkRepository,
                SuccessfulCollectionsRepository()
            )
            val client = pipeline.setup(
                environment = SynchronizationEnvironment(server.baseUrl),
                localModificationDateFetcher = dateFetcher,
                authenticationDataFetcher = LoggedInAuthenticationDataFetcher,
                callback = object : SyncEngineCallback {
                    override fun synchronizationDone(newLastModificationDate: Long) {
                        dateFetcher.updateLastModificationDate(newLastModificationDate)
                        completion.complete(Unit)
                    }

                    override fun encounteredError(errorMsg: String) = Unit
                }
            )

            try {
                client.triggerSyncImmediately()
                withTimeout(5_000) {
                    completion.await()
                }
            } finally {
                client.cancelSyncing()
            }

            assertTrue(server.getQueries.single().contains("mutationsSince=0"))
            assertTrue(server.postBodies.single().filterNot(Char::isWhitespace).contains(""""key":42"""))
            assertEquals(listOf("pending-1"), bookmarkRepository.clearedLocalIds)
            assertEquals(2_000L, settings.getLong("com.quran.sync.last_modified_date", -1L))
            assertEquals(1, settings.getInt("com.quran.sync.protocol_version", 0))
        }
    }

    @Test
    fun `timestamp completion is not reported when a later resource fails`() = runBlocking {
        EmptySyncServer().use { server ->
            val completionTokens = CopyOnWriteArrayList<Long>()
            val bookmarkRepository = RecordingBookmarksRepository()
            val collectionRepository = FailingCollectionsRepository(completionTokens)
            val pipeline = SyncEnginePipeline(bookmarkRepository, collectionRepository)
            val client = pipeline.setup(
                environment = SynchronizationEnvironment(server.baseUrl),
                localModificationDateFetcher = object : LocalModificationDateFetcher {
                    override suspend fun localLastModificationDate(): Long = 100L
                },
                authenticationDataFetcher = object : AuthenticationDataFetcher {
                    override suspend fun fetchAuthenticationHeaders(): Map<String, String> =
                        emptyMap()

                    override fun isLoggedIn(): Boolean = true
                },
                callback = object : SyncEngineCallback {
                    override fun synchronizationDone(newLastModificationDate: Long) {
                        completionTokens += newLastModificationDate
                    }

                    override fun encounteredError(errorMsg: String) = Unit
                }
            )

            try {
                client.triggerSyncImmediately()
                withTimeout(5_000) {
                    collectionRepository.failureAttempted.await()
                }
            } finally {
                client.cancelSyncing()
            }

            assertTrue(bookmarkRepository.applied)
            assertFalse(collectionRepository.completionObservedBeforeFailure)
            assertTrue(completionTokens.isEmpty())
        }
    }
}

private object LoggedInAuthenticationDataFetcher : AuthenticationDataFetcher {
    override suspend fun fetchAuthenticationHeaders(): Map<String, String> = emptyMap()
    override fun isLoggedIn(): Boolean = true
}

private class PendingBookmarksRepository : BookmarksSynchronizationRepository {
    private val pending: LocalModelMutation<Bookmark> = LocalModelMutation(
        model = Bookmark.PageBookmark(
            page = 42,
            lastUpdated = Instant.fromEpochMilliseconds(500),
            localId = "pending-1"
        ),
        remoteID = null,
        localID = "pending-1",
        mutation = Mutation.CREATED
    )
    val clearedLocalIds = mutableListOf<String>()

    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<Bookmark>> =
        listOf(pending)

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark>>,
        localMutationsToClear: List<LocalModelMutation<Bookmark>>
    ) {
        clearedLocalIds += localMutationsToClear.map { it.localID }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        emptyMap()

    override suspend fun fetchBookmarkByRemoteId(remoteId: String): Bookmark? = null
}

private class SuccessfulCollectionsRepository : CollectionsSynchronizationRepository {
    override suspend fun fetchMutatedCollections(): List<LocalModelMutation<Collection>> =
        emptyList()

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteCollection>>,
        localMutationsToClear: List<LocalModelMutation<Collection>>
    ) = Unit

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        emptyMap()
}

private class RecordingBookmarksRepository : BookmarksSynchronizationRepository {
    var applied = false

    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<Bookmark>> = emptyList()

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark>>,
        localMutationsToClear: List<LocalModelMutation<Bookmark>>
    ) {
        applied = true
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        emptyMap()

    override suspend fun fetchBookmarkByRemoteId(remoteId: String): Bookmark? = null
}

private class FailingCollectionsRepository(
    private val completionTokens: List<Long>
) : CollectionsSynchronizationRepository {
    val failureAttempted = CompletableDeferred<Unit>()
    var completionObservedBeforeFailure = false

    override suspend fun fetchMutatedCollections(): List<LocalModelMutation<Collection>> =
        emptyList()

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteCollection>>,
        localMutationsToClear: List<LocalModelMutation<Collection>>
    ) {
        completionObservedBeforeFailure = completionTokens.isNotEmpty()
        failureAttempted.complete(Unit)
        throw IllegalStateException("collection persistence failed")
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        emptyMap()
}

private class EmptySyncServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl: String

    init {
        server.createContext("/v1/sync") { exchange ->
            val body = """
                {
                  "success": true,
                  "data": {
                    "lastMutationAt": 2000,
                    "mutations": [],
                    "page": 1,
                    "hasMore": false
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    override fun close() {
        server.stop(0)
    }
}

private class ReplaySyncServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val getQueries = CopyOnWriteArrayList<String>()
    val postBodies = CopyOnWriteArrayList<String>()
    val baseUrl: String

    init {
        server.createContext("/v1/sync") { exchange ->
            val response = if (exchange.requestMethod == "GET") {
                getQueries += exchange.requestURI.rawQuery
                """
                    {
                      "success": true,
                      "data": {
                        "lastMutationAt": 2000,
                        "mutations": [],
                        "page": 1,
                        "hasMore": false
                      }
                    }
                """.trimIndent()
            } else {
                postBodies += exchange.requestBody.bufferedReader().use { it.readText() }
                """
                    {
                      "success": true,
                      "data": {
                        "lastMutationAt": 2000,
                        "mutations": [{
                          "type": "CREATE",
                          "resource": "BOOKMARK",
                          "resourceId": "remote-1",
                          "timestamp": 2000
                        }]
                      }
                    }
                """.trimIndent()
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    override fun close() {
        server.stop(0)
    }
}

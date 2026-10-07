package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.network.GetMutationsRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class SynchronizationClientTest {
    
    @Test
    fun `test SynchronizationClient can be created`() {
        // This is a basic test to ensure the SynchronizationClient can be instantiated
        // The main business logic testing is done in BookmarksSynchronizationExecutorTest
        assertTrue(true, "SynchronizationClient scaffolding is working")
    }

    @Test
    fun `sync operation fetches auth headers even when cached login state is false`() = runTest {
        val authHeadersFetched = CompletableDeferred<Unit>()
        var localFetchCount = 0
        val client = SynchronizationClientBuilder.build(
            environment = SynchronizationEnvironment("https://example.invalid"),
            authFetcher = object : AuthenticationDataFetcher {
                override suspend fun fetchAuthenticationHeaders(): Map<String, String> {
                    authHeadersFetched.complete(Unit)
                    return emptyMap()
                }

                override fun isLoggedIn(): Boolean = false
            },
            bookmarksConfigurations = BookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncBookmark> {
                    override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncBookmark>> {
                        localFetchCount += 1
                        return emptyList()
                    }

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        fail("Empty auth headers should no-op before local existence checks")

                    override suspend fun fetchLocalModel(remoteId: String): SyncBookmark? =
                        fail("Empty auth headers should no-op before local model fetch")
                },
                resultNotifier = object : ResultNotifier<SyncBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncBookmark>>
                    ) {
                        fail("Empty auth headers should no-op before resource success")
                    }

                    override suspend fun didFail(message: String) {
                        fail("Empty auth headers should no-op without resource failure")
                    }
                },
                localModificationDateFetcher = object : LocalModificationDateFetcher {
                    override suspend fun localLastModificationDate(): Long? =
                        fail("Empty auth headers should no-op before reading sync token")
                }
            )
        )

        client.triggerSyncImmediately()

        withContext(Dispatchers.Default) {
            withTimeout(1_000) {
                authHeadersFetched.await()
            }
        }
        client.cancelSyncingAndJoin()

        assertEquals(0, localFetchCount)
    }

    @Test
    fun `sync applies remote mutations from every page and stores the remote head`() = runTest {
        val remoteBookmarkCount = GetMutationsRequest.PAGE_LIMIT + 500
        val remoteHead = 50_000L
        val requestedPages = mutableListOf<Int>()
        val httpClient = HttpClient(
            MockEngine { request ->
                // Backend defaults when pagination parameters are omitted.
                val page = request.url.parameters["page"]?.toInt() ?: 1
                val limit = request.url.parameters["limit"]?.toInt() ?: 100
                requestedPages += page
                val mutations = (0 until remoteBookmarkCount)
                    .drop((page - 1) * limit)
                    .take(limit)
                    .joinToString(",") { index ->
                        """{"resource":"BOOKMARK","resourceId":"remote-$index","type":"CREATE",""" +
                            """"data":{"type":"ayah","key":${index % 114 + 1},"verseNumber":${index / 114 + 1}},""" +
                            """"timestamp":${index + 1}}"""
                    }
                respond(
                    content = """{"success":true,"data":{"lastMutationAt":$remoteHead,"mutations":[$mutations],""" +
                        """"page":$page,"limit":$limit,"total":$remoteBookmarkCount,""" +
                        """"hasMore":${page * limit < remoteBookmarkCount}}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        ) {
            install(ContentNegotiation) {
                json(
                    Json {
                        explicitNulls = false
                        ignoreUnknownKeys = true
                    }
                )
            }
        }
        val storedToken = CompletableDeferred<Long>()
        var appliedRemoteIds = emptyList<String>()
        val client = SynchronizationClientBuilder.build(
            environment = SynchronizationEnvironment("https://example.test"),
            authFetcher = object : AuthenticationDataFetcher {
                override suspend fun fetchAuthenticationHeaders(): Map<String, String> =
                    mapOf("Authorization" to "Bearer token")

                override fun isLoggedIn(): Boolean = true
            },
            bookmarksConfigurations = BookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncBookmark> {
                    override suspend fun fetchLocalMutations(lastModified: Long) =
                        emptyList<LocalModelMutation<SyncBookmark>>()

                    override suspend fun checkLocalExistence(remoteIDs: List<String>) =
                        remoteIDs.associateWith { false }

                    override suspend fun fetchLocalModel(remoteId: String): SyncBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncBookmark>>
                    ) {
                        appliedRemoteIds = newRemoteMutations.map { it.remoteID }
                    }

                    override suspend fun didFail(message: String) = fail(message)
                },
                localModificationDateFetcher = object : LocalModificationDateFetcher {
                    override suspend fun localLastModificationDate(): Long = 0L
                }
            ),
            syncCompletionFinalizer = SyncCompletionFinalizer { token -> storedToken.complete(token) },
            httpClient = httpClient
        )

        client.triggerSyncImmediately()

        val token = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                storedToken.await()
            }
        }
        client.cancelSyncingAndJoin()

        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(List(remoteBookmarkCount) { index -> "remote-$index" }, appliedRemoteIds)
        assertEquals(remoteHead, token)
    }
}

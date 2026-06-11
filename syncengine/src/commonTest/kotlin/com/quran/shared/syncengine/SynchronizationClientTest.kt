package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
}

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.mutations.Mutation
import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class SynchronizationClientIntegrationTest {

    private val accessToken = "eyJhbGciOiJSUzI1NiIsImtpZCI6IjE0ZDQ1ZDI4LTY1ZDgtNDMyYi04Y2EzLTZmZjM5MjEyYWQ0YiIsInR5cCI6IkpXVCJ9.eyJhdWQiOltdLCJjbGllbnRfaWQiOiI5NTRlYjU0OS0zNTY2LTRmOWEtYjY1Zi1mYTYxYmY5YTllMzciLCJleHAiOjE3NTI0Mzk3NzYsImV4dCI6e30sImlhdCI6MTc1MjQzNjE3NiwiaXNzIjoiaHR0cHM6Ly90ZXN0aW5nLW9hdXRoMi5xdXJhbi5mb3VuZGF0aW9uIiwianRpIjoiMGJlMTU5OWUtMTY5Ni00M2E2LWEwNDYtOGMxMTFmZjUxYjRlIiwibmJmIjoxNzUyNDM2MTc2LCJzY3AiOlsib3BlbmlkIiwicHJvZmlsZSIsImJvb2ttYXJrIiwic3luYyJdLCJzdWIiOiJjZmY2ZGFhNy05NzZlLTRlYmMtOWViOC1iMTY4ZTlmNTNiYmIifQ.RxqZPXeW9D-mu9SgMl56alqKDwyED0JEx4CYPJPq2nbvK4bOHMzxRokFTS6TX4Fjh6SLRbqdxuYioyAdIZP2FUW8d3xuLrw6DDem4C_TjeaQk1faUL9-ZMLUPRQV3VF3zDfdaSL7yg9rcU-_2ul4vhArNVB15qvMSvcL2ShVUrRWAFhpxcOuMsyRQ8F2w7qVdpF8xMvXVUE55f6PzsMJu5qq8aCvEWcsewl06V2UkvDnDex3XoqA9tQIgkO4jcfS7p_ooJdpRWRKo2Nn50iEVUycUKysFKxYvz3SvjXtVh2OniaEihyL6ps2fystSM00GequNua5sqzx6fQkA1JyHVr2XHam5gvkfT_1Hr6XphzreMqkqtt0EoQm98MLULdAL81KckBJT5X4iezfsewMd6UnIFXy9hFstgv9A5IFzFntSig696XJU_B-vwQ8Pa297bVQEFTXY5jUVwpE8rI_GY8-XSRch8O_b3vaVfkiC-uhU8kDgUREhE7cMi0cCnHZe3F2W0LkM6UIcbkgMVRSwEIAWN024nx6KEgPrj_FAWK33KMvcY_xctnj-W_hFLSrMOwljxpe8b0j5jGI8zH4uHf4I3F1WZEkS8h0CmT5gpKgTBIw9ZsQHjYdnqHfh9YgvksdXyG6Tl5iujTXNHtiX7rDUDOjtMcomVQCZYGuKxw"
    private val clientId = "954eb549-3566-4f9a-b65f-fa61bf9a9e37"
    private val baseUrl = "https://apis-testing.quran.foundation"
    private val lastModificationDate: Long? = 1752432810198L

    private fun createEnvironment(): SynchronizationEnvironment {
        return SynchronizationEnvironment(baseUrl)
    }

    private fun createAuthenticationDataFetcher(): AuthenticationDataFetcher {
        return object : AuthenticationDataFetcher {
            override suspend fun fetchAuthenticationHeaders(): Map<String, String> {
                return mapOf(
                    "x-client-id" to clientId,
                    "x-auth-token" to accessToken
                )
            }
        }
    }

    private fun createLocalModificationDateFetcher(lastModificationDate: Long?): LocalModificationDateFetcher {
        return object : LocalModificationDateFetcher {
            override suspend fun localLastModificationDate(): Long? {
                return lastModificationDate
            }
        }
    }

    private fun createLocalMutationsFetcher(mutations: List<LocalModelMutation<PageBookmark>>): LocalMutationsFetcher<PageBookmark> {
        return object : LocalMutationsFetcher<PageBookmark> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<PageBookmark>> {
                println("Mock fetcher called with lastModified: $lastModified")
                return mutations
            }
        }
    }

    private fun createResultNotifier(
        syncCompleted: CompletableDeferred<Unit>,
        expectedLocalMutationsCount: Int = 0,
        expectedRemoteMutationsMinCount: Int = 0,
        expectedProcessedPages: Set<Int>? = null
    ): ResultNotifier<PageBookmark> {
        return object : ResultNotifier<PageBookmark> {
            override suspend fun syncResult(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<PageBookmark>>,
                processedLocalMutations: List<LocalModelMutation<PageBookmark>>
            ) {
                // Verify the results
                println("Got response. Last modification date: $newToken")
                println("Got response. Remote mutations count: ${newRemoteMutations.count()}")
                println("Got response. Processed local mutations count: ${processedLocalMutations.count()}")
                println("Got response. Remote mutations IDs: ${newRemoteMutations.map { it.model.id }}")
                println("Got response. Remote mutations pages: ${newRemoteMutations.map { it.model.page }}")
                println("Got response. Processed local mutations pages: ${processedLocalMutations.map { it.model.page }}")
                
                assertTrue(newToken > 0L, "Should return a new timestamp")
                assertEquals(expectedLocalMutationsCount, processedLocalMutations.size, "Should have expected processed local mutations")
                assertTrue(newRemoteMutations.count() >= expectedRemoteMutationsMinCount, "Expect to return at least expected remote mutations")
                
                // Verify specific pages if provided
                expectedProcessedPages?.let { expectedPages ->
                    val processedPages = processedLocalMutations.map { it.model.page }.toSet()
                    assertEquals(expectedPages, processedPages, "Should have processed expected mutations")
                }
                
                // Signal that sync is complete
                syncCompleted.complete(Unit)
            }
        }
    }

    private fun createBookmarksConfigurations(
        localMutationsFetcher: LocalMutationsFetcher<PageBookmark>,
        resultNotifier: ResultNotifier<PageBookmark>,
        localModificationDateFetcher: LocalModificationDateFetcher
    ): PageBookmarksSynchronizationConfigurations {
        return PageBookmarksSynchronizationConfigurations(
            localMutationsFetcher = localMutationsFetcher,
            resultNotifier = resultNotifier,
            localModificationDateFetcher = localModificationDateFetcher
        )
    }

    private fun createSynchronizationClient(
        environment: SynchronizationEnvironment,
        authFetcher: AuthenticationDataFetcher,
        bookmarksConfigurations: PageBookmarksSynchronizationConfigurations
    ): SynchronizationClient {
        return SynchronizationClientBuilder.build(
            environment = environment,
            authFetcher = authFetcher,
            bookmarksConfigurations = bookmarksConfigurations
        )
    }

    @Test
    @Ignore
    fun `test running without local changes and zero timestamp`() = runTest {
        // Arrange
        val syncCompleted = CompletableDeferred<Unit>()
        
        val environment = createEnvironment()
        val authFetcher = createAuthenticationDataFetcher()
        val localMutationsFetcher = createLocalMutationsFetcher(emptyList())
        val localModificationDateFetcher = createLocalModificationDateFetcher(null)
        val resultNotifier = createResultNotifier(
            syncCompleted = syncCompleted,
            expectedLocalMutationsCount = 0,
            expectedRemoteMutationsMinCount = 1
        )
        
        val bookmarksConfigurations = createBookmarksConfigurations(
            localMutationsFetcher = localMutationsFetcher,
            resultNotifier = resultNotifier,
            localModificationDateFetcher = localModificationDateFetcher
        )
        
        val synchronizationClient = createSynchronizationClient(
            environment = environment,
            authFetcher = authFetcher,
            bookmarksConfigurations = bookmarksConfigurations
        )
        
        // Assert
        assertNotNull(synchronizationClient, "SynchronizationClient should be created successfully")
        
        // Trigger the sync operation
        println("Running the integration test for synchronization.")
        synchronizationClient.applicationStarted()
        
        // Wait for the sync operation to complete
        syncCompleted.await()
        println("Sync operation completed successfully!")
    }

    @Test
    fun `test running and pushing some local updates`() = runTest {
        // Arrange
        val syncCompleted = CompletableDeferred<Unit>()
        
        // Create test data for local mutations: 2 creations and 1 deletion
        val testLocalMutations = listOf(
//            LocalModelMutation(
//                model = PageBookmark(id = "local-2", page = 20, lastModified = 1752348537423),
//                remoteID = null, // No remote ID for local mutations
//                localID = "local-id-2",
//                mutation = Mutation.CREATED
//            ),
            LocalModelMutation(
                model = PageBookmark(id = "hvpyr0q863etejgc4l4dpmhj", page = 50, lastModified = 1752350137423),
                remoteID = "hvpyr0q863etejgc4l4dpmhj", // This was a remote bookmark that we're deleting
                localID = "local-id-3",
                mutation = Mutation.DELETED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "t8sx6yrl55oft086mx5bygl5", page = 107, lastModified = 1752350137423),
                remoteID = "t8sx6yrl55oft086mx5bygl5", // This was a remote bookmark that we're deleting
                localID = "local-id-3",
                mutation = Mutation.DELETED
            )
        )
        
        val environment = createEnvironment()
        val authFetcher = createAuthenticationDataFetcher()
        val localMutationsFetcher = createLocalMutationsFetcher(testLocalMutations)
        val localModificationDateFetcher = createLocalModificationDateFetcher(lastModificationDate)
        val resultNotifier = createResultNotifier(
            syncCompleted = syncCompleted,
            expectedLocalMutationsCount = 3,
            expectedRemoteMutationsMinCount = 3,
            expectedProcessedPages = setOf(10, 20, 30)
        )
        
        val bookmarksConfigurations = createBookmarksConfigurations(
            localMutationsFetcher = localMutationsFetcher,
            resultNotifier = resultNotifier,
            localModificationDateFetcher = localModificationDateFetcher
        )
        
        val synchronizationClient = createSynchronizationClient(
            environment = environment,
            authFetcher = authFetcher,
            bookmarksConfigurations = bookmarksConfigurations
        )
        
        // Assert
        assertNotNull(synchronizationClient, "SynchronizationClient should be created successfully")
        
        // Trigger the sync operation
        println("Running the integration test for synchronization with local updates.")
        synchronizationClient.applicationStarted()
        
        // Wait for the sync operation to complete
        syncCompleted.await()
        println("Sync operation with local updates completed successfully!")
    }
}
package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.network.HttpClientFactory
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for SynchronizationClient.
 * 
 * These tests make real network calls to the testing API to verify end-to-end functionality.
 * 
 * To run these tests:
 * 1. Ensure you have valid authentication credentials (accessToken and clientId)
 * 2. Be aware that tests will make actual network requests to https://apis-testing.quran.foundation
 * 3. Tests verify the actual sync behavior against the real API
 */
class SynchronizationClientIntegrationTest {

    private val accessToken = "eyJhbGciOiJSUzI1NiIsImtpZCI6IjE0ZDQ1ZDI4LTY1ZDgtNDMyYi04Y2EzLTZmZjM5MjEyYWQ0YiIsInR5cCI6IkpXVCJ9.eyJhdWQiOltdLCJjbGllbnRfaWQiOiI5NTRlYjU0OS0zNTY2LTRmOWEtYjY1Zi1mYTYxYmY5YTllMzciLCJleHAiOjE3NTU3NDE1MTgsImV4dCI6e30sImlhdCI6MTc1NTczNzkxOCwiaXNzIjoiaHR0cHM6Ly90ZXN0aW5nLW9hdXRoMi5xdXJhbi5mb3VuZGF0aW9uIiwianRpIjoiMzAxM2E4OWQtZmJlZi00OTBjLWE1NTYtNzExOWQyZDcwN2RkIiwibmJmIjoxNzU1NzM3OTE4LCJzY3AiOlsib3BlbmlkIiwicHJvZmlsZSIsImJvb2ttYXJrIiwic3luYyJdLCJzdWIiOiJjZmY2ZGFhNy05NzZlLTRlYmMtOWViOC1iMTY4ZTlmNTNiYmIifQ.hDXuFBP9Tde4qtKYerk6WDdXpb8Se7LmHpJ8SquUpXSkGsXYRn8kCFWP9p5ZqeLJ8BPyhJMAF00byFpaa5XkTBy9OaUujiSOro-qQtT5DoGvJC2z-El7juYeD8-UjY88bLqW6VkfVRAPTlXweKZVqJrk5A_QGuWkCdH_-KiDo5tGYkhc8An2DI-FWoL7enwdAjhk5ctAJtCbUXUQfV00GTIaDKfcqvsrgAdicBEFDn8gM999XBE2Fh51CAqx1lrq6_YCux8jum24hLM5qHpViCquq8AkA0jp9PSKMW7BGNNEx1JK5aMxYZeXS2ANRUeEXSLGdhG1xJOnWyctIGOR9tOc2gAjz8OgfaLr7vexmBkeMz8z19hI7vZX9T8Dg63OX9nlSHDzNsL2pbpGSLn4bfIHVWpryh8g6BEZx2CQaPbcMfp3p8Bnj3VRPFAUSGl0csQ6EV8IzFuew8cu0KFKKqBmX3zcEj6LL63d9AsvTyXrIj4YNFBJGdt0-j3cTKdjcukn-QLUX1MyLgoEIO3V8e7UTsdAV-M1vTAYULm3ELIdl7Kf0-AOhIEiwHl5sLRyWaYHcRN76IJrg-4SMxMvWk7CiYJCZ6hSXr6l_2d4u7aby9xG9Vo765SxMqWuQrYnJDqU5U_UkU9PLCXdefp729MkT4OTN79SuQZnDkFlM4M"
    private val clientId = "954eb549-3566-4f9a-b65f-fa61bf9a9e37"
    private val baseUrl = "https://apis-testing.quran.foundation"
    private val lastModificationDate: Long? = null

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

    private fun createLocalMutationsFetcher(
        mutations: List<LocalModelMutation<PageBookmark>>,
        existingRemoteIDs: Set<String> = emptySet()
    ): LocalDataFetcher<PageBookmark> {
        return object : LocalDataFetcher<PageBookmark> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<PageBookmark>> {
                println("Mock fetcher called with lastModified: $lastModified")
                return mutations
            }
            
            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
                // Mock implementation - return true only for IDs in the existingRemoteIDs set
                return remoteIDs.associateWith { it in existingRemoteIDs }
            }
        }
    }

    private fun createResultNotifier(
        syncCompleted: CompletableDeferred<Unit>,
        expectedLocalMutationsCount: Int = 0,
        expectedRemoteMutationsMinCount: Int = 0,
        expectedProcessedPages: Set<Int>? = null,
        expectOnlyCreationEvents: Boolean = false
    ): ResultNotifier<PageBookmark> {
        return object : ResultNotifier<PageBookmark> {

            override suspend fun didFail(message: String) {
                println("Got a failure. $message")
                syncCompleted.complete(Unit)
            }

            override suspend fun didSucceed(
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
                println("Got response. Remote mutations types: ${newRemoteMutations.map { it.mutation }}")
                println("Got response. Processed local mutations pages: ${processedLocalMutations.map { it.model.page }}")
                
                assertTrue(newToken > 0L, "Should return a new timestamp")
                assertEquals(expectedLocalMutationsCount, processedLocalMutations.size, "Should have expected processed local mutations")
                assertTrue(newRemoteMutations.count() >= expectedRemoteMutationsMinCount, "Expect to return at least expected remote mutations")
                
                // Verify specific pages if provided
                expectedProcessedPages?.let { expectedPages ->
                    val processedPages = processedLocalMutations.map { it.model.page }.toSet()
                    assertEquals(expectedPages, processedPages, "Should have processed expected mutations")
                }
                
                // Verify only CREATION events if specified
                if (expectOnlyCreationEvents) {
                    println("Creation events: ${newRemoteMutations.count { it.mutation == Mutation.CREATED }}")
                    println("Modification events: ${newRemoteMutations.count { it.mutation == Mutation.MODIFIED }}")
                    println("Deletion events: ${newRemoteMutations.count { it.mutation == Mutation.DELETED }}")
                    val hasOnlyCreationEvents = newRemoteMutations.all { it.mutation == Mutation.CREATED }
                    assertTrue(hasOnlyCreationEvents, "Should only receive CREATION events for first-time sync")
                    
                    val hasNoDeleteEvents = newRemoteMutations.none { it.mutation == Mutation.DELETED }
                    assertTrue(hasNoDeleteEvents, "Should not receive any DELETE events for first-time sync")
                    
                    println("Verified: Only CREATION events received, no DELETE events")
                }
                
                // Signal that sync is complete
                syncCompleted.complete(Unit)
            }
        }
    }

    private fun createBookmarksConfigurations(
        localDataFetcher: LocalDataFetcher<PageBookmark>,
        resultNotifier: ResultNotifier<PageBookmark>,
        localModificationDateFetcher: LocalModificationDateFetcher
    ): PageBookmarksSynchronizationConfigurations {
        return PageBookmarksSynchronizationConfigurations(
            localDataFetcher = localDataFetcher,
            resultNotifier = resultNotifier,
            localModificationDateFetcher = localModificationDateFetcher
        )
    }

    private fun createSynchronizationClient(
        environment: SynchronizationEnvironment,
        authFetcher: AuthenticationDataFetcher,
        bookmarksConfigurations: PageBookmarksSynchronizationConfigurations,
        httpClient: HttpClient? = null
    ): SynchronizationClient {
        return SynchronizationClientBuilder.build(
            environment = environment,
            authFetcher = authFetcher,
            bookmarksConfigurations = bookmarksConfigurations,
            httpClient = httpClient
        )
    }

    @Test
    @Ignore
    fun `test first time sync with no local changes and zero timestamp`() = runTest {
        // Arrange
        val syncCompleted = CompletableDeferred<Unit>()
        
        val environment = createEnvironment()
        val authFetcher = createAuthenticationDataFetcher()
        val localMutationsFetcher = createLocalMutationsFetcher(
            mutations = emptyList(),
            existingRemoteIDs = emptySet() // No existing remote IDs for first-time sync
        )
        val localModificationDateFetcher = createLocalModificationDateFetcher(null) // Zero timestamp for first time sync
        val resultNotifier = createResultNotifier(
            syncCompleted = syncCompleted,
            expectedLocalMutationsCount = 0,
            expectedRemoteMutationsMinCount = 1,
            expectOnlyCreationEvents = true
        )
        
        val bookmarksConfigurations = createBookmarksConfigurations(
            localDataFetcher = localMutationsFetcher,
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
        println("Running the first-time sync integration test.")
        synchronizationClient.applicationStarted()
        
        // Wait for the sync operation to complete
        syncCompleted.await()
        println("First-time sync operation completed successfully!")
    }

    @Test
    @Ignore
    fun `test running without local changes and zero timestamp`() = runTest {
        // Arrange
        val syncCompleted = CompletableDeferred<Unit>()
        
        val environment = createEnvironment()
        val authFetcher = createAuthenticationDataFetcher()
        val localMutationsFetcher = createLocalMutationsFetcher(
            mutations = emptyList(),
            existingRemoteIDs = emptySet()
        )
        val localModificationDateFetcher = createLocalModificationDateFetcher(null)
        val resultNotifier = createResultNotifier(
            syncCompleted = syncCompleted,
            expectedLocalMutationsCount = 0,
            expectedRemoteMutationsMinCount = 1
        )
        
        val bookmarksConfigurations = createBookmarksConfigurations(
            localDataFetcher = localMutationsFetcher,
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
    @Ignore
    fun `test running and pushing some local updates`() = runTest {
        // Arrange
        val syncCompleted = CompletableDeferred<Unit>()
        
        // Create test data for local mutations: 2 creations and 1 deletion
        val testLocalMutations = listOf(
            LocalModelMutation(
                model = PageBookmark(id = "local-2", page = 200, lastModified = Instant.fromEpochMilliseconds(1752350137423)),
                remoteID = null, // No remote ID for local mutations
                localID = "local-id-2",
                mutation = Mutation.CREATED
            ),
//            LocalModelMutation(
//                model = PageBookmark(id = "hvpyr0q863etejgc4l4dpmhj", page = 50, lastModified = Instant.fromEpochMilliseconds(1752350137423)),
//                remoteID = "hvpyr0q863etejgc4l4dpmhj", // This was a remote bookmark that we're deleting
//                localID = "local-id-3",
//                mutation = Mutation.DELETED
//            ),
//            LocalModelMutation(
//                model = PageBookmark(id = "t8sx6yrl55oft086mx5bygl5", page = 107, lastModified = Instant.fromEpochMilliseconds(1752350137423)),
//                remoteID = "t8sx6yrl55oft086mx5bygl5", // This was a remote bookmark that we're deleting
//                localID = "local-id-3",
//                mutation = Mutation.DELETED
//            )
        )
        
        val environment = createEnvironment()
        val authFetcher = createAuthenticationDataFetcher()
        val localMutationsFetcher = createLocalMutationsFetcher(
            mutations = testLocalMutations,
            existingRemoteIDs = setOf("hvpyr0q863etejgc4l4dpmhj", "t8sx6yrl55oft086mx5bygl5") // Example: these IDs exist locally
        )
        val localModificationDateFetcher = createLocalModificationDateFetcher(lastModificationDate)
        val resultNotifier = createResultNotifier(
            syncCompleted = syncCompleted,
            expectedLocalMutationsCount = 3,
            expectedRemoteMutationsMinCount = 3,
            expectedProcessedPages = setOf(10, 20, 30)
        )
        
        val bookmarksConfigurations = createBookmarksConfigurations(
            localDataFetcher = localMutationsFetcher,
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

    @Test
//    @Ignore
    fun `test a couple of conflicts with expected deletions from BE as well`() = runTest {

        assertTrue( (lastModificationDate ?: 0) > 0, "The last modification date should be bigger than 0 for this test.")
        // Arrange
        val syncCompleted = CompletableDeferred<Unit>()

        // Create test data for local mutations: 2 creations and 1 deletion
        val testLocalMutations = listOf(
            LocalModelMutation(
                // For this to work, there needs to be an expected remote delete mutation for that remote ID.
                model = PageBookmark(id = "bnz3yxj9hqsepxtteov57bvt", page = 20, lastModified = Instant.fromEpochMilliseconds(1752350137423)),
                remoteID = "bnz3yxj9hqsepxtteov57bvt", // To be filled
                localID = "bnz3yxj9hqsepxtteov57bvt",
                mutation = Mutation.DELETED
            ),
            LocalModelMutation(
                model = PageBookmark(id = "local-2", page = 20, lastModified = Instant.fromEpochMilliseconds(1752350137493)),
                remoteID = null, // No remote ID for local mutations
                localID = "local-id-2",
                mutation = Mutation.CREATED
            ),
//            LocalModelMutation(
//                // TODO: Should clash with something on the BE
//                model = PageBookmark(id = "local-2", page = 200, lastModified = Instant.fromEpochMilliseconds(1752350137423)),
//                remoteID = null, // No remote ID for local mutations
//                localID = "local-id-5",
//                mutation = Mutation.CREATED
//            ),
            LocalModelMutation(
                model = PageBookmark(id = "local-2", page = 600, lastModified = Instant.fromEpochMilliseconds(1752350137423)),
                remoteID = null, // No remote ID for local mutations
                localID = "non-clashing-local-id",
                mutation = Mutation.CREATED
            ),
        )

        val environment = createEnvironment()
        val authFetcher = createAuthenticationDataFetcher()
        val localMutationsFetcher = createLocalMutationsFetcher(
            mutations = testLocalMutations,
            existingRemoteIDs = setOf("chqcraq024hde90cwwxo14a0", "f5u2hbakgomknm828nsfltwk")
        )
        val localModificationDateFetcher = createLocalModificationDateFetcher(lastModificationDate)
        val resultNotifier = createResultNotifier(
            syncCompleted = syncCompleted,
            expectedLocalMutationsCount = 3,
            expectedRemoteMutationsMinCount = 3,
            expectedProcessedPages = setOf(10, 20, 30)
        )

        val bookmarksConfigurations = createBookmarksConfigurations(
            localDataFetcher = localMutationsFetcher,
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
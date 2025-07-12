package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class SynchronizationClientIntegrationTest {

    @Test
    fun `test running without local changes and zero timestamp`() = runTest {
        // Arrange
        val environment = SynchronizationEnvironment("https://apis-testing.quran.foundation")
        val syncCompleted = CompletableDeferred<Unit>()
        
        val mockLocalMutationsFetcher = object : LocalMutationsFetcher<PageBookmark> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<PageBookmark>> {
                // Return empty list for no local changes
                return emptyList()
            }
        }
        
        val mockResultNotifier = object : ResultNotifier<PageBookmark> {
            override suspend fun syncResult(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<PageBookmark>>,
                processedLocalMutations: List<LocalModelMutation<PageBookmark>>
            ) {
                // Verify the results
                println("Got response. Last modification date: $newToken")
                println("Got response. Remote mutations count: ${newRemoteMutations.count()}")
                println("Got response. Remote mutations IDs: ${newRemoteMutations.map { it.model.id }}")
                assertTrue(newToken > 0L, "Should return a new timestamp")
                assertEquals(0, processedLocalMutations.size, "Should have no processed local mutations")
                assertTrue(newRemoteMutations.count() > 0, "Expect to return some remote mutations.")
                // Note: newRemoteMutations will depend on what the server returns
                
                // Signal that sync is complete
                syncCompleted.complete(Unit)
            }
        }
        
        val mockLocalModificationDateFetcher = object : LocalModificationDateFetcher {
            override suspend fun localLastModificationDate(): Long? {
                // Return null to indicate no previous sync (zero timestamp)
                return null
            }
        }
        
        val mockAuthenticationDataFetcher = object : AuthenticationDataFetcher {
            override suspend fun fetchAuthenticationHeaders(): Map<String, String> {
                return mapOf(
                    "x-client-id" to "954eb549-3566-4f9a-b65f-fa61bf9a9e37",
                    "x-auth-token" to "eyJhbGciOiJSUzI1NiIsImtpZCI6IjE0ZDQ1ZDI4LTY1ZDgtNDMyYi04Y2EzLTZmZjM5MjEyYWQ0YiIsInR5cCI6IkpXVCJ9.eyJhdWQiOltdLCJjbGllbnRfaWQiOiI5NTRlYjU0OS0zNTY2LTRmOWEtYjY1Zi1mYTYxYmY5YTllMzciLCJleHAiOjE3NTIzNTg0OTEsImV4dCI6e30sImlhdCI6MTc1MjM1NDg5MSwiaXNzIjoiaHR0cHM6Ly90ZXN0aW5nLW9hdXRoMi5xdXJhbi5mb3VuZGF0aW9uIiwianRpIjoiMTA5MGQzMjktZGExMy00NmVlLTljYTYtYTFhMDg1MDk3NDgwIiwibmJmIjoxNzUyMzU0ODkxLCJzY3AiOlsib3BlbmlkIiwicHJvZmlsZSIsImJvb2ttYXJrIiwic3luYyJdLCJzdWIiOiJjZmY2ZGFhNy05NzZlLTRlYmMtOWViOC1iMTY4ZTlmNTNiYmIifQ.skLsWgSwf2RVG45SEWsNAFzQPLb0tX0qoz25Y3Gzl7_vJ8JvOxOAOFdGnRGSFvl4_95REW3xQ1Hfi4cWcUfWyIo99-ycmYpwrS8GZ1O6yL3MQvP8Gq1Wu3Fb9hI83dR1IkWJ9c3EWsEFTbcZV5swpH_WZ4jzGEjuSZhPIkWMWFdCYrkJ1-FqnMsLaz0svBEbjugBEeVM9BUCKY-OpjAEP1by0kmRbtqm6FZjhsgo9aKDWOvGTck1Z6vFVa1ERtpq4rUn71zJ_Q0SYpSkI1AhP0RcWXOCVE0NDQI7yUHKWD0Ez8NmZewSGNSZl2-rz2pMc0h550Im5RBgDRM7ddyqSDu1pw6MDenD-HqwYpE8JpYGaolVztk6ccYnseJG4gxokt-SBxceBdbvbZn1Gijzhvn5fHTEk-N_V8nFJvkFyrEyuCU0WugY0yL9D-cvWPqKxYmGVTTSJcV9z2ZLHnpPx2jpxeYrgmsX1PkHlqdXStCqbxzMQlmX0dwdCZmHvn9dlfMyU_UcdxuBwhBAC3v2u5qEURLfrEPSwOE8HtVKt2uTX4GijWctAN3izvjpwtGa3c3DP8BOkjvUrycVfMdSrEqIJdqhyYaVkLFE_gFY_rsV19172Ehcgjr-62nfSDtmrW8Z3buEeoAlzEjp6aFg3LeXVq6Mx3vNCHaPfaI-4_0"
                )
            }
        }
        
        val bookmarksConfigurations = PageBookmarksSynchronizationConfigurations(
            localMutationsFetcher = mockLocalMutationsFetcher,
            resultNotifier = mockResultNotifier,
            localModificationDateFetcher = mockLocalModificationDateFetcher
        )
        
        // Act
        val synchronizationClient = SynchronizationClientBuilder.build(
            environment = environment,
            authFetcher = mockAuthenticationDataFetcher,
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
}
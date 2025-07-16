package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

internal class SynchronizationClientImpl(
    private val environment: SynchronizationEnvironment,
    private val httpClient: HttpClient,
    private val bookmarksConfigurations: PageBookmarksSynchronizationConfigurations,
    private val authenticationDataFetcher: AuthenticationDataFetcher): SynchronizationClient {

    private val logger = Logger.withTag("SynchronizationClient")
    private var authHeaders: Map<String, String>? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun localDataUpdated() {
        logger.i { "Local data updated, starting sync operation" }
        // TODO: Will need to schedule this.
        scope.launch {
            startSyncOperation()
        }
    }

    override fun applicationStarted() {
        logger.i { "Application started, starting sync operation" }
        // TODO: Will need to schedule this.
        scope.launch {
            startSyncOperation()
        }
    }

    private suspend fun startSyncOperation() {
        try {
            logger.i { "Starting sync operation" }
            val lastModificationDate = bookmarksConfigurations.localModificationDateFetcher
                .localLastModificationDate() ?: 0L

            val localMutations = bookmarksConfigurations.localMutationsFetcher
                .fetchLocalMutations(lastModificationDate)

            val fetchRemoteModificationsResult = fetchRemoteModifications(lastModificationDate)

            val remoteModifications = fetchRemoteModificationsResult.mutations
            val updatedModificationDate = fetchRemoteModificationsResult.lastModificationDate
            logger.d { "Fetched ${remoteModifications.size} remote modifications, updated modification date: $updatedModificationDate" }

            // Get the list of local mutations not overridden by the BE.
            val remoteIDs = remoteModifications.map { it.remoteID }.toSet()
            val remotePages = remoteModifications.map { it.model.page }.toSet()
            val filteredLocalMutations = localMutations.filter { local ->
                remotePages.contains(local.model.page).not() && remoteIDs.contains(local.remoteID).not()
            }
            logger.d { "Filtered local mutations from ${localMutations.size} to ${filteredLocalMutations.size}" }

            val pushMutationsResult = pushLocalMutations(filteredLocalMutations, updatedModificationDate)
            val pushedRemoteMutations = pushMutationsResult.mutations
            logger.d { "Pushed ${filteredLocalMutations.size} local mutations, received ${pushedRemoteMutations.size} pushed remote mutations" }

            val mergedRemoteModelMutation = pushedRemoteMutations + remoteModifications
            logger.d { "Merged remote mutations: ${mergedRemoteModelMutation.size} total" }

            logger.i { "Sync operation completed, notifying result with ${mergedRemoteModelMutation.size} remote mutations and ${localMutations.size} local mutations" }
            bookmarksConfigurations.resultNotifier.didSucceed(
                pushMutationsResult.lastModificationDate,
                mergedRemoteModelMutation,
                localMutations
            )
        } catch (e: Exception) {
            logger.e(e) { "Sync operation failed: ${e.message}" }
            bookmarksConfigurations.resultNotifier.didFail("Sync operation failed: ${e.message}")
        }
    }

    private suspend fun fetchRemoteModifications(lastModificationDate: Long): MutationsResponse {
        logger.d { "Fetching remote modifications from ${environment.endPointURL} with last modification date: $lastModificationDate" }
        val authHeaders = getAuthHeaders()
        val url = environment.endPointURL
        val request = GetMutationsRequest(httpClient, url)
        return request.getMutations(lastModificationDate, authHeaders)
    }

    private suspend fun pushLocalMutations(
        mutations: List<LocalModelMutation<PageBookmark>>,
        lastModificationDate: Long): MutationsResponse {
        if (mutations.isEmpty()) {
            logger.d { "No local mutations to push. Returning" }
            return MutationsResponse(lastModificationDate, listOf())
        }
        logger.d { "Pushing ${mutations.size} local mutations to ${environment.endPointURL} with last modification date: $lastModificationDate" }
        val authHeaders = getAuthHeaders()
        val url = environment.endPointURL
        val request = PostMutationsRequest(httpClient, url)
        return request.postMutations(mutations, lastModificationDate, authHeaders)
    }

    private suspend fun getAuthHeaders(): Map<String, String> {
        if (this.authHeaders == null) {
            logger.d { "Fetching authentication headers" }
            this.authHeaders = authenticationDataFetcher.fetchAuthenticationHeaders()
            logger.d { "Authentication headers fetched, count: ${this.authHeaders?.size ?: 0}" }
        }
        return this.authHeaders ?: mapOf()
     }
}
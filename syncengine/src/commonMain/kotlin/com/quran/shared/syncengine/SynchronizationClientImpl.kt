package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

internal class SynchronizationClientImpl(
    private val environment: SynchronizationEnvironment,
    private val bookmarksConfigurations: PageBookmarksSynchronizationConfigurations,
    private val authenticationDataFetcher: AuthenticationDataFetcher): SynchronizationClient {

    private var authHeaders: Map<String, String>? = null
    private val httpClient = HttpClientFactory.createHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun localDataUpdated() {
        // TODO: Will need to schedule this.
        scope.launch {
            startSyncOperation()
        }
    }

    override fun applicationStarted() {
        // TODO: Will need to schedule this.
        scope.launch {
            startSyncOperation()
        }
    }

    private suspend fun startSyncOperation() {
        // fetch local changes
        // fetch remote changes
        // upon conflict, ignore local changes
        // push local changes
        // retrieve new objects created by pushing local changes
        // deliver local changes, and time stamp
        val lastModificationDate = bookmarksConfigurations.localModificationDateFetcher
            .localLastModificationDate() ?: 0L
        val localMutations = bookmarksConfigurations.localMutationsFetcher
            .fetchLocalMutations(lastModificationDate)

        val fetchRemoteModificationsResult = fetchRemoteModifications(lastModificationDate)
        val remoteModifications = fetchRemoteModificationsResult.mutations
        val updatedModificationDate = fetchRemoteModificationsResult.lastModificationDate

        // Get the list of local mutations not overridden by the BE.
        val remoteIDs = remoteModifications.map { it.remoteID }.toSet()
        val remotePages = remoteModifications.map { it.model.page }.toSet()
        val filteredLocalMutations = localMutations.filter { local ->
            remotePages.contains(local.model.page).not() && remoteIDs.contains(local.remoteID)
        }

        val pushMutationsResult = pushLocalMutations(filteredLocalMutations, updatedModificationDate)
        val pushedRemoteMutations = pushMutationsResult.mutations

        val mergedRemoteModelMutation = pushedRemoteMutations + remoteModifications

        bookmarksConfigurations.resultNotifier.syncResult(
            pushMutationsResult.lastModificationDate,
            mergedRemoteModelMutation,
            localMutations
        )
    }

    private suspend fun fetchRemoteModifications(lastModificationDate: Long): MutationsResponse {
        val authHeaders = getAuthHeaders()
        val url = environment.endPointURL
        val request = GetMutationsRequest(httpClient, url)
        return request.getMutations(lastModificationDate, authHeaders)
    }

    private suspend fun pushLocalMutations(
        mutations: List<LocalModelMutation<PageBookmark>>,
        lastModificationDate: Long): MutationsResponse {
        val authHeaders = getAuthHeaders()
        val url = environment.endPointURL
        val request = PostMutationsRequestClient(httpClient, url)
        return request.postMutations(mutations, lastModificationDate, authHeaders)
    }

    private suspend fun getAuthHeaders(): Map<String, String> {
        if (this.authHeaders == null) {
            this.authHeaders = authenticationDataFetcher.fetchAuthenticationHeaders()
        }
        return this.authHeaders ?: mapOf()
     }
}
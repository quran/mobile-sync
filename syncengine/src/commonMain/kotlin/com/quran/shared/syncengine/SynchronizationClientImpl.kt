package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.syncengine.network.GetMutationsRequest
import com.quran.shared.syncengine.network.MutationsResponse
import com.quran.shared.syncengine.network.PostMutationsRequest
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
            
            val pipeline = PageBookmarksSynchronizationExecutor()
            val result = pipeline.executePipeline(
                fetchLocal = { initializePipeline() },
                fetchRemote = { lastModificationDate -> fetchRemoteModificationsPipeline(lastModificationDate) },
                checkLocalExistence = { remoteIDs -> checkLocalExistence(remoteIDs) },
                pushLocal = { mutations, lastModificationDate -> pushMutationsPipeline(mutations, lastModificationDate) }
            )
            
            logger.i { "Sync operation completed successfully with ${result.remoteMutations.size} remote mutations to persist and ${result.localMutations.size} local mutations to clear." }
            bookmarksConfigurations.resultNotifier.didSucceed(
                result.lastModificationDate,
                result.remoteMutations,
                result.localMutations
            )
            
        } catch (e: Exception) {
            logger.e(e) { "Sync operation failed: ${e.message}" }
            bookmarksConfigurations.resultNotifier.didFail("Sync operation failed: ${e.message}")
        }
    }

    private suspend fun pushLocalMutations(
        mutations: List<LocalModelMutation<PageBookmark>>,
        lastModificationDate: Long): MutationsResponse {
        if (mutations.isEmpty()) {
            logger.d { "No local mutations to push, skipping network request" }
            return MutationsResponse(lastModificationDate, listOf())
        }
        
        logger.i { "Pushing ${mutations.size} local mutations" }
        val authHeaders = getAuthHeaders()
        val url = environment.endPointURL
        val request = PostMutationsRequest(httpClient, url)
        val response = request.postMutations(mutations, lastModificationDate, authHeaders)
        logger.i { "Successfully pushed mutations: received ${response.mutations.size} pushed remote mutations" }
        return response
    }

    private suspend fun getAuthHeaders(): Map<String, String> {
        // TODO: Should fail at this point!
        if (this.authHeaders == null) {
            logger.d { "Fetching authentication headers from external source" }
            this.authHeaders = authenticationDataFetcher.fetchAuthenticationHeaders()
            logger.d { "Authentication headers fetched: ${this.authHeaders?.size ?: 0} headers" }
        } else {
            logger.d { "Using cached authentication headers: ${this.authHeaders?.size ?: 0} headers" }
        }
        return this.authHeaders ?: mapOf()
    }

    // Pipeline Step Methods (now simplified to work with PageBookmarksSynchronizationExecutor)
    private suspend fun initializePipeline(): PageBookmarksSynchronizationExecutor.PipelineInitData {
        logger.d { "Fetching local data from repository" }
        val lastModificationDate = bookmarksConfigurations.localModificationDateFetcher
            .localLastModificationDate() ?: 0L
        val localMutations = bookmarksConfigurations.localDataFetcher
            .fetchLocalMutations(lastModificationDate)
        logger.i { "Local data fetched: lastModificationDate=$lastModificationDate, localMutations=${localMutations.size}" }
        return PageBookmarksSynchronizationExecutor.PipelineInitData(lastModificationDate, localMutations)
    }

    private suspend fun fetchRemoteModificationsPipeline(lastModificationDate: Long): PageBookmarksSynchronizationExecutor.FetchedRemoteData {
        logger.d { "Fetching remote modifications from ${environment.endPointURL} with lastModificationDate=$lastModificationDate" }
        val authHeaders = getAuthHeaders()
        val url = environment.endPointURL
        val request = GetMutationsRequest(httpClient, url)
        val result = request.getMutations(lastModificationDate, authHeaders)
        return PageBookmarksSynchronizationExecutor.FetchedRemoteData(result.mutations, result.lastModificationDate)
    }

    private suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
        logger.d { "Checking local existence for ${remoteIDs.size} remote IDs" }
        return bookmarksConfigurations.localDataFetcher.checkLocalExistence(remoteIDs)
    }

    private suspend fun pushMutationsPipeline(
        localMutations: List<LocalModelMutation<PageBookmark>>,
        lastModificationDate: Long
    ): PageBookmarksSynchronizationExecutor.PushResultData {
        logger.d { "Executing push mutations for ${localMutations.size} mutations" }
        val result = pushLocalMutations(localMutations, lastModificationDate)
        logger.i { "Push mutations completed: ${result.mutations.size} pushed remote mutations received" }
        return PageBookmarksSynchronizationExecutor.PushResultData(result.mutations, result.lastModificationDate)
    }
}
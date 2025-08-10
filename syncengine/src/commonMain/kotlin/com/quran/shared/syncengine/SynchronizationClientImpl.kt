package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
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
            logger.i { "Starting sync operation pipeline" }
            
            val pipeline = PageBookmarksSynchronizationExecutor()
            pipeline.executePipeline(
                fetchLocal = { initializePipeline() },
                fetchRemote = { lastModificationDate -> fetchRemoteModificationsPipeline(lastModificationDate) },
                checkLocalExistence = { remoteIDs -> checkLocalExistence(remoteIDs) },
                pushLocal = { mutations, lastModificationDate -> pushMutationsPipeline(mutations, lastModificationDate) },
                deliverResult = { result -> 
                    logger.d { "Pipeline Step 11: Complete - Notifying success" }
                    logger.i { "Sync operation pipeline completed successfully with ${result.remoteMutations.size} remote mutations and ${result.localMutations.size} local mutations" }
                    bookmarksConfigurations.resultNotifier.didSucceed(
                        result.lastModificationDate,
                        result.remoteMutations,
                        result.localMutations
                    )
                }
            )
            
        } catch (e: Exception) {
            logger.e(e) { "Sync operation pipeline failed: ${e.message}" }
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

    // Pipeline Step Methods (now simplified to work with PageBookmarksSynchronizationExecutor)
    private suspend fun initializePipeline(): PageBookmarksSynchronizationExecutor.PipelineInitData {
        logger.d { "Pipeline Step 1: Initialize - Getting last modification date and local mutations" }
        val lastModificationDate = bookmarksConfigurations.localModificationDateFetcher
            .localLastModificationDate() ?: 0L
        val localMutations = bookmarksConfigurations.localDataFetcher
            .fetchLocalMutations(lastModificationDate)
        logger.d { "Initialized with lastModificationDate=$lastModificationDate, localMutations=${localMutations.size}" }
        return PageBookmarksSynchronizationExecutor.PipelineInitData(lastModificationDate, localMutations)
    }

    private suspend fun fetchRemoteModificationsPipeline(lastModificationDate: Long): PageBookmarksSynchronizationExecutor.FetchedRemoteData {
        logger.d { "Pipeline Step 2: Fetch - Getting remote modifications from server" }
        val result = fetchRemoteModifications(lastModificationDate)
        logger.d { "Fetched ${result.mutations.size} remote modifications, updated modification date: ${result.lastModificationDate}" }
        return PageBookmarksSynchronizationExecutor.FetchedRemoteData(result.mutations, result.lastModificationDate)
    }

    private suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
        logger.d { "Pipeline Step 4: Preprocess - Checking local existence for ${remoteIDs.size} remote IDs" }
        return bookmarksConfigurations.localDataFetcher.checkLocalExistence(remoteIDs)
    }

    private suspend fun pushMutationsPipeline(
        localMutations: List<LocalModelMutation<PageBookmark>>,
        lastModificationDate: Long
    ): PageBookmarksSynchronizationExecutor.PushResultData {
        logger.d { "Pipeline Step 7: Push - Sending local mutations to server" }
        val result = pushLocalMutations(localMutations, lastModificationDate)
        logger.d { "Pushed ${localMutations.size} local mutations, received ${result.mutations.size} pushed remote mutations" }
        return PageBookmarksSynchronizationExecutor.PushResultData(result.mutations, result.lastModificationDate)
    }
}
package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.syncengine.network.GetMutationsRequest
import com.quran.shared.syncengine.network.MutationsResponse
import com.quran.shared.syncengine.network.PostMutationsRequest
import com.quran.shared.syncengine.scheduling.Scheduler
import com.quran.shared.syncengine.scheduling.Trigger
import com.quran.shared.syncengine.scheduling.createScheduler
import io.ktor.client.HttpClient

internal class SynchronizationClientImpl(
    private val environment: SynchronizationEnvironment,
    private val httpClient: HttpClient,
    private val bookmarksConfigurations: PageBookmarksSynchronizationConfigurations,
    private val authenticationDataFetcher: AuthenticationDataFetcher): SynchronizationClient {

    private val logger = Logger.withTag("SynchronizationClient")
    
    private val scheduler: Scheduler = createScheduler(
        taskFunction = ::startSyncOperation,
        reachedMaximumFailureRetries = { exception ->
            logger.e(exception) { "Sync operation failed after maximum retries: ${exception.message}" }
            bookmarksConfigurations.resultNotifier.didFail("Sync operation failed after maximum retries: ${exception.message}")
        }
    )

    override fun localDataUpdated() {
        logger.i { "Local data updated, triggering scheduler" }
        scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
    }

    override fun applicationStarted() {
        logger.i { "Application started, triggering scheduler" }
        scheduler.invoke(Trigger.APP_REFRESH)
    }

    private suspend fun startSyncOperation() {
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
        logger.d { "Fetching fresh authentication headers from external source" }
        val headers = authenticationDataFetcher.fetchAuthenticationHeaders()
        logger.d { "Authentication headers fetched: ${headers.size} headers" }
        return headers
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
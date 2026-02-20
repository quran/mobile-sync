package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
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
    private val resourceAdapters: List<SyncResourceAdapter>,
    private val authenticationDataFetcher: AuthenticationDataFetcher): SynchronizationClient {

    private val logger = Logger.withTag("SynchronizationClient")
    
    private val scheduler: Scheduler = createScheduler(
        taskFunction = ::startSyncOperation,
        reachedMaximumFailureRetries = { exception ->
            val message = "Sync operation failed after maximum retries: ${exception.message}"
            logger.e(exception) { message }
            resourceAdapters.forEach { adapter ->
                adapter.didFail(message)
            }
        }
    )

    override fun localDataUpdated() {
        if (authenticationDataFetcher.isLoggedIn()) {
            logger.i { "Local data updated, triggering scheduler" }
            scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        } else {
            logger.d { "Local data updated but user is not logged in, skipping trigger" }
        }
    }

    override fun applicationStarted() {
        if (authenticationDataFetcher.isLoggedIn()) {
            logger.i { "Application started, triggering scheduler" }
            scheduler.invoke(Trigger.APP_REFRESH)
        } else {
            logger.d { "Application started but user is not logged in, skipping trigger" }
        }
    }

    override fun triggerSyncImmediately() {
        logger.i { "Triggering immediate sync" }
        scheduler.invoke(Trigger.IMMEDIATE)
    }

    override fun cancelSyncing() {
        logger.i { "Cancelling all scheduled operations" }
        scheduler.cancel()
    }

    private suspend fun startSyncOperation() {
        if (!authenticationDataFetcher.isLoggedIn()) {
            logger.i { "User is not logged in, skipping sync operation" }
            return
        }

        if (resourceAdapters.isEmpty()) {
            logger.w { "No sync resources configured, skipping sync operation" }
            return
        }

        logger.i { "Starting sync operation for ${resourceAdapters.size} resource(s)" }

        val authHeaders = getAuthHeaders()
        // Assume a shared sync token across resources.
        val lastModificationDate = resourceAdapters.first()
            .localModificationDateFetcher
            .localLastModificationDate() ?: 0L

        val resources = resourceAdapters.map { it.resourceName }.distinct()
        val remoteResponse = fetchRemoteMutations(lastModificationDate, authHeaders, resources)

        val plans = resourceAdapters.map { adapter ->
            adapter.buildPlan(lastModificationDate, remoteResponse.mutations)
        }

        val mutationsToPush = plans.flatMap { it.mutationsToPush() }
        val pushResponse = pushMutations(mutationsToPush, remoteResponse.lastModificationDate, authHeaders)

        val pushedMutationsByResource = pushResponse.mutations.groupBy { it.resource.uppercase() }
        plans.forEach { plan ->
            val pushedForResource = pushedMutationsByResource[plan.resourceName.uppercase()].orEmpty()
            plan.complete(pushResponse.lastModificationDate, pushedForResource)
        }
    }

    private suspend fun pushMutations(
        mutations: List<SyncMutation>,
        lastModificationDate: Long,
        authHeaders: Map<String, String>
    ): MutationsResponse {
        if (mutations.isEmpty()) {
            logger.d { "No local mutations to push, skipping network request" }
            return MutationsResponse(lastModificationDate, listOf())
        }
        
        logger.i { "Pushing ${mutations.size} local mutations" }
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

    private suspend fun fetchRemoteMutations(
        lastModificationDate: Long,
        authHeaders: Map<String, String>,
        resources: List<String>
    ): MutationsResponse {
        logger.d {
            "Fetching remote modifications from ${environment.endPointURL} with " +
                "lastModificationDate=$lastModificationDate, resources=${resources.joinToString(",")}"
        }
        val url = environment.endPointURL
        val request = GetMutationsRequest(httpClient, url)
        return request.getMutations(lastModificationDate, authHeaders, resources)
    }
}

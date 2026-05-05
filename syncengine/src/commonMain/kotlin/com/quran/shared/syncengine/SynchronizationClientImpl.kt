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

        executeDependencyAwareSync(
            resourceAdapters = resourceAdapters,
            initialLastModificationDate = lastModificationDate,
            remoteResponse = remoteResponse
        ) { mutations, mutationToken ->
            pushMutations(mutations, mutationToken, authHeaders)
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

private val PRIMARY_SYNC_RESOURCES = setOf("BOOKMARK", "COLLECTION")
private const val COLLECTION_BOOKMARK_SYNC_RESOURCE = "COLLECTION_BOOKMARK"

internal fun List<SyncResourceAdapter>.dependencyAwareSyncPhases(): List<List<SyncResourceAdapter>> {
    val remainingAdapters = toMutableList()
    val phases = mutableListOf<List<SyncResourceAdapter>>()

    fun addPhase(resourceNames: Set<String>) {
        val phase = remainingAdapters.filter { adapter ->
            adapter.resourceName.uppercase() in resourceNames
        }
        if (phase.isNotEmpty()) {
            phases += phase
            remainingAdapters.removeAll(phase)
        }
    }

    addPhase(PRIMARY_SYNC_RESOURCES)
    addPhase(setOf(COLLECTION_BOOKMARK_SYNC_RESOURCE))

    if (remainingAdapters.isNotEmpty()) {
        phases += remainingAdapters
    }

    return phases
}

internal suspend fun executeDependencyAwareSync(
    resourceAdapters: List<SyncResourceAdapter>,
    initialLastModificationDate: Long,
    remoteResponse: MutationsResponse,
    pushMutations: suspend (List<SyncMutation>, Long) -> MutationsResponse
) {
    var mutationToken = remoteResponse.lastModificationDate
    val safeCompletionToken = remoteResponse.lastModificationDate
    val phases = resourceAdapters.dependencyAwareSyncPhases()
    val totalPlans = phases.sumOf { it.size }

    phases.forEach { phaseAdapters ->
        val plans = phaseAdapters.map { adapter ->
            adapter.buildPlan(initialLastModificationDate, remoteResponse.mutations)
        }

        val mutationsToPush = plans.flatMap { it.mutationsToPush() }
        val pushResponse = pushMutations(mutationsToPush, mutationToken)
        mutationToken = pushResponse.lastModificationDate

        val pushedMutationsByResource = pushResponse.mutations.groupBy { it.resource.uppercase() }
        plans.forEach { plan ->
            val pushedForResource = pushedMutationsByResource[plan.resourceName.uppercase()].orEmpty()
            val completionToken = if (totalPlans == 1) mutationToken else initialLastModificationDate
            plan.complete(completionToken, pushedForResource)
        }
    }

    if (totalPlans > 1) {
        resourceAdapters.forEach { adapter ->
            adapter.didCompleteSync(safeCompletionToken)
        }
    }
}

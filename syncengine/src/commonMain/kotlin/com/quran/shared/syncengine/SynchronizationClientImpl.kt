package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.network.GetMutationsRequest
import com.quran.shared.syncengine.network.MutationsResponse
import com.quran.shared.syncengine.network.PostMutationsRequest
import com.quran.shared.syncengine.scheduling.Scheduler
import com.quran.shared.syncengine.scheduling.Trigger
import com.quran.shared.syncengine.scheduling.createScheduler
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class SynchronizationClientImpl(
    private val environment: SynchronizationEnvironment,
    private val httpClient: HttpClient,
    private val resourceAdapters: List<SyncResourceAdapter>,
    private val authenticationDataFetcher: AuthenticationDataFetcher,
    private val syncCompletionFinalizer: SyncCompletionFinalizer,
    private val syncLifecycleGate: SyncLifecycleGate
) : SynchronizationClient {

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
        if (syncLifecycleGate.canStartSync()) {
            logger.i { "Local data updated, triggering scheduler" }
            scheduler.invoke(Trigger.LOCAL_DATA_MODIFIED)
        } else {
            logger.d { "Local data updated but sync lifecycle is unavailable, skipping trigger" }
        }
    }

    override fun applicationStarted() {
        if (syncLifecycleGate.canStartSync()) {
            logger.i { "Application started, triggering scheduler" }
            scheduler.invoke(Trigger.APP_REFRESH)
        } else {
            logger.d { "Application started but sync lifecycle is unavailable, skipping trigger" }
        }
    }

    override fun triggerSyncImmediately() {
        if (syncLifecycleGate.canStartSync()) {
            logger.i { "Triggering immediate sync" }
            scheduler.invoke(Trigger.IMMEDIATE)
        } else {
            logger.d { "Immediate sync requested but sync lifecycle is unavailable, skipping trigger" }
        }
    }

    override fun cancelSyncing() {
        logger.i { "Cancelling all scheduled operations" }
        scheduler.cancel()
    }

    override suspend fun cancelSyncingAndJoin() {
        logger.i { "Cancelling and draining all scheduled operations" }
        scheduler.cancelAndJoin()
    }

    private suspend fun startSyncOperation() {
        val syncEpoch = try {
            if (!syncLifecycleGate.canStartSync()) {
                logger.i { "Sync lifecycle is unavailable, skipping sync operation" }
                return
            }
            syncLifecycleGate.captureSyncEpoch()
        } catch (exception: SyncOperationInvalidatedException) {
            logger.i { "Sync operation invalidated before start: ${exception.message}" }
            return
        }

        if (resourceAdapters.isEmpty()) {
            logger.w { "No sync resources configured, skipping sync operation" }
            return
        }

        logger.i { "Starting sync operation for ${resourceAdapters.size} resource(s)" }

        val authHeaders = getAuthHeaders()
        if (authHeaders.isEmpty()) {
            logger.i { "No authentication headers available, skipping sync operation" }
            return
        }

        // Assume a shared sync token across resources.
        val lastModificationDate = resourceAdapters.first()
            .localModificationDateFetcher
            .localLastModificationDate() ?: 0L

        val resources = resourceAdapters.map { it.resourceName }.distinct()
        val remoteResponse = fetchRemoteMutations(lastModificationDate, authHeaders, resources)

        try {
            executeDependencyAwareSync(
                resourceAdapters = resourceAdapters,
                initialLastModificationDate = lastModificationDate,
                remoteResponse = remoteResponse,
                pushMutations = { mutations, mutationToken, admitPost ->
                    syncLifecycleGate.checkSyncEpoch(syncEpoch)
                    if (mutations.isNotEmpty()) {
                        syncLifecycleGate.admitSyncPost(syncEpoch)
                        admitPost()
                    }
                    val response = pushMutations(mutations, mutationToken, authHeaders)
                    syncLifecycleGate.checkSyncEpoch(syncEpoch)
                    response
                },
                preparePush = { mutations ->
                    syncLifecycleGate.checkSyncEpoch(syncEpoch)
                    mutations.isNotEmpty()
                },
                checkSyncStillValid = {
                    syncLifecycleGate.checkSyncEpoch(syncEpoch)
                },
                completeSync = { token ->
                    syncCompletionFinalizer.completeSync(token)
                }
            )
        } catch (exception: SyncOperationInvalidatedException) {
            logger.i { "Sync operation invalidated, aborting without retry: ${exception.message}" }
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
    pushMutations: suspend (List<SyncMutation>, Long, admitPost: () -> Unit) -> MutationsResponse,
    preparePush: suspend (List<SyncMutation>) -> Boolean = { mutations -> mutations.isNotEmpty() },
    checkSyncStillValid: suspend () -> Unit = {},
    completeSync: suspend (Long) -> Unit = {}
) {
    var mutationToken = remoteResponse.lastModificationDate
    val initialGetToken = remoteResponse.lastModificationDate
    var pushedPlanCount = 0
    val preDependencyDeletionPlans = resourceAdapters
        .mapNotNull { adapter ->
            (adapter as? PreDependencyDeletionSyncResourceAdapter)
                ?.buildPreDependencyDeletionPlan(initialLastModificationDate, remoteResponse.mutations)
        }
    val phases = resourceAdapters.dependencyAwareSyncPhases()
    val totalPlans = phases.sumOf { it.size } + preDependencyDeletionPlans.size

    suspend fun executePlans(plans: List<ResourceSyncPlan>): List<SyncMutation> {
        if (plans.isEmpty()) {
            return emptyList()
        }

        val markedPlans = mutableListOf<ResourceSyncPlan>()
        val completedPlans = mutableListOf<ResourceSyncPlan>()
        var mutationsToPush: List<SyncMutation> = emptyList()
        var postMayHaveBeenAttempted = false
        try {
            plans.forEach { plan ->
                markedPlans += plan
                withContext(NonCancellable) {
                    plan.markMutationsInFlight()
                }
            }
            currentCoroutineContext().ensureActive()
            mutationsToPush = buildList {
                plans.forEach { plan ->
                    val planMutations = plan.mutationsToPush()
                    if (planMutations.isNotEmpty()) {
                        pushedPlanCount += 1
                    }
                    addAll(planMutations)
                }
            }
            checkSyncStillValid()
            // Keep durable create markers once a POST could have reached the backend so replay can bind
            // accepted remote IDs to local tombstones. Rollback below is only local preflight cleanup.
            preparePush(mutationsToPush)
            var postAdmitted = false
            val pushResponse = try {
                pushMutations(mutationsToPush, mutationToken) {
                    postAdmitted = true
                    postMayHaveBeenAttempted = mutationsToPush.isNotEmpty()
                }
            } catch (exception: Throwable) {
                if (!postAdmitted && exception !is SyncOperationInvalidatedException) {
                    postMayHaveBeenAttempted = mutationsToPush.isNotEmpty()
                }
                throw exception
            }
            if (!postAdmitted) {
                postMayHaveBeenAttempted = mutationsToPush.isNotEmpty()
            }
            checkSyncStillValid()
            validatePushedMutationResponse(mutationsToPush, pushResponse.mutations)
            mutationToken = pushResponse.lastModificationDate

            val pushedMutationsByResource = pushResponse.mutations.groupBy { it.resource.uppercase() }
            plans.forEach { plan ->
                val pushedForResource = pushedMutationsByResource[plan.resourceName.uppercase()].orEmpty()
                checkSyncStillValid()
                withContext(NonCancellable + SyncWriteBoundaryContext(checkSyncStillValid)) {
                    checkSyncStillValid()
                    plan.complete(initialLastModificationDate, pushedForResource)
                    completedPlans += plan
                }
                currentCoroutineContext().ensureActive()
            }
        } catch (exception: Throwable) {
            if (!postMayHaveBeenAttempted) {
                withContext(NonCancellable) {
                    markedPlans.filterNot { plan -> plan in completedPlans }.forEach { plan ->
                        plan.rollbackMutationsInFlight()
                    }
                }
            }
            throw exception
        }
        return mutationsToPush
    }

    val acceptedPreDependencyDeletes = executePlans(preDependencyDeletionPlans)
        .filter { mutation -> mutation.mutation == Mutation.DELETED }
        .mapNotNull { mutation ->
            mutation.resourceId?.let { resourceId -> mutation.resource.uppercase() to resourceId }
        }
        .toSet()
    val remoteMutationsForNormalPhases = if (acceptedPreDependencyDeletes.isEmpty()) {
        remoteResponse.mutations
    } else {
        remoteResponse.mutations.filterNot { mutation ->
            val resourceId = mutation.effectiveCreatedResourceId()
            mutation.mutation == Mutation.CREATED &&
                resourceId != null &&
                (mutation.resource.uppercase() to resourceId) in acceptedPreDependencyDeletes
        }
    }

    phases.forEach { phaseAdapters ->
        val plans = phaseAdapters.map { adapter ->
            adapter.buildPlan(initialLastModificationDate, remoteMutationsForNormalPhases)
        }
        executePlans(plans)
    }

    checkSyncStillValid()
    val finalToken = if (pushedPlanCount > 0) initialGetToken else mutationToken
    withContext(NonCancellable + SyncWriteBoundaryContext(checkSyncStillValid)) {
        checkSyncStillValid()
        completeSync(finalToken)
    }
}

private fun SyncMutation.effectiveCreatedResourceId(): String? {
    if (mutation != Mutation.CREATED || !resource.equals(COLLECTION_BOOKMARK_SYNC_RESOURCE, ignoreCase = true)) {
        return resourceId
    }
    resourceId?.let { return it }
    val collectionId = data?.stringOrNull("collectionId")
    val bookmarkId = data?.stringOrNull("bookmarkId")
        ?: data?.stringOrNull("bookmark_id")
    return if (!collectionId.isNullOrEmpty() && !bookmarkId.isNullOrEmpty()) {
        "$collectionId-$bookmarkId"
    } else {
        null
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

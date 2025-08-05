package com.quran.shared.syncengine

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.mutations.Mutation
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

            val localMutations = bookmarksConfigurations.localDataFetcher
                .fetchLocalMutations(lastModificationDate)

            val fetchRemoteModificationsResult = fetchRemoteModifications(lastModificationDate)

            val remoteModifications = fetchRemoteModificationsResult.mutations
            val updatedModificationDate = fetchRemoteModificationsResult.lastModificationDate
            logger.d { "Fetched ${remoteModifications.size} remote modifications, updated modification date: $updatedModificationDate" }

            // Preprocess remote mutations to filter out DELETE and MODIFIED mutations for non-existent local resources
            val preprocessor = RemoteMutationsPreprocessor(bookmarksConfigurations.localDataFetcher)
            val preprocessedRemoteMutations = preprocessor.preprocess(remoteModifications)
            logger.d { "Preprocessed remote mutations: ${preprocessedRemoteMutations.size} out of ${remoteModifications.size} mutations" }

            // Use ConflictDetector to detect conflicts
            val conflictDetector = ConflictDetector(preprocessedRemoteMutations, localMutations)
            val conflictDetectionResult = conflictDetector.getConflicts()
            
            logger.d { "Conflict detection completed: ${conflictDetectionResult.conflictGroups.size} conflict groups, " +
                "${conflictDetectionResult.otherRemoteMutations.size} non-conflicting remote mutations, " +
                "${conflictDetectionResult.otherLocalMutations.size} non-conflicting local mutations" }

            // Use ConflictResolver to resolve conflicts
            val conflictResolver = ConflictResolver(conflictDetectionResult.conflictGroups)
            val conflictResolutionResult = conflictResolver.resolve()
            
            logger.d { "Conflict resolution completed: ${conflictResolutionResult.mutationsToPersist.size} mutations to persist, " +
                "${conflictResolutionResult.mutationsToPush.size} mutations to push" }

            // Combine non-conflicting local mutations from detector with mutations to push from resolver
            val allLocalMutationsToPush = conflictDetectionResult.otherLocalMutations + conflictResolutionResult.mutationsToPush
            
            // Push all local mutations that need to be pushed
            val pushMutationsResult = pushLocalMutations(allLocalMutationsToPush, updatedModificationDate)
            val pushedRemoteMutations = pushMutationsResult.mutations
            logger.d { "Pushed ${allLocalMutationsToPush.size} local mutations, received ${pushedRemoteMutations.size} pushed remote mutations" }

            // Combine all remote mutations: non-conflicting from detector + mutations to persist from resolver + pushed mutations
            val allRemoteMutations = conflictDetectionResult.otherRemoteMutations + 
                conflictResolutionResult.mutationsToPersist + 
                pushedRemoteMutations
            logger.d { "Combined remote mutations: ${allRemoteMutations.size} total" }

            logger.i { "Sync operation completed, notifying result with ${allRemoteMutations.size} remote mutations and ${localMutations.size} local mutations" }
            bookmarksConfigurations.resultNotifier.didSucceed(
                pushMutationsResult.lastModificationDate,
                allRemoteMutations,
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
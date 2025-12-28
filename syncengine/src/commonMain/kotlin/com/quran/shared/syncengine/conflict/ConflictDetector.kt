package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.SyncBookmarkKey
import com.quran.shared.syncengine.model.conflictKey
import com.quran.shared.syncengine.model.conflictKeyOrNull

/**
 * 
 * @param conflicts Groups of mutations that have conflicts
 * @param nonConflictingRemoteMutations Remote mutations that don't conflict with any local mutations
 * @param nonConflictingLocalMutations Local mutations that don't conflict with any remote mutations
 */
data class ConflictDetectionResult<Model>(
    val conflicts: List<ResourceConflict<Model>>,
    val nonConflictingRemoteMutations: List<RemoteModelMutation<Model>>,
    val nonConflictingLocalMutations: List<LocalModelMutation<Model>>
)

/**
 * Detects conflicts between local and remote mutations for bookmarks.
 *
 * A conflict is detected whenever a set of local and remote mutations reference the same bookmark
 * key, or when a remote deletion is missing resource data but matches a local remote ID.
 *
 * The detector groups conflicts by bookmark key and provides separate lists for non-conflicting mutations.
 */
class ConflictDetector(
    private val remoteMutations: List<RemoteModelMutation<SyncBookmark>>,
    private val localMutations: List<LocalModelMutation<SyncBookmark>>
) {

    fun getConflicts(): ConflictDetectionResult<SyncBookmark> {
        val remoteMutationsByKey = remoteMutations
            .map { mutation ->
                mutation.model.conflictKeyOrNull().let { key -> key to mutation }
            }
            .groupBy({ it.first }, { it.second })
        val remoteMutationsByRemoteID = remoteMutations.associateBy { it.remoteID }
        
        val resourceConflicts = buildResourceConflicts(remoteMutationsByKey, remoteMutationsByRemoteID)
        val conflictingIDs = extractConflictingIDs(resourceConflicts)
        
        return buildResult(resourceConflicts, conflictingIDs)
    }
    
    /**
     * Builds resource conflicts by analyzing local mutations and finding corresponding remote conflicts.
     */
    private fun buildResourceConflicts(
        remoteMutationsByKey: Map<SyncBookmarkKey, List<RemoteModelMutation<SyncBookmark>>>,
        remoteMutationsByRemoteID: Map<String, RemoteModelMutation<SyncBookmark>>
    ): List<ResourceConflict<SyncBookmark>> {
        return localMutations
            .groupBy { it.model.conflictKey() }
            .mapNotNull { (bookmarkKey, localMutations) ->
                val conflictingRemoteMutations = findConflictingRemoteMutations(
                    bookmarkKey,
                    localMutations, 
                    remoteMutationsByKey,
                    remoteMutationsByRemoteID
                )
                
                if (conflictingRemoteMutations.isNotEmpty()) {
                    ResourceConflict(
                        localMutations = localMutations,
                        remoteMutations = conflictingRemoteMutations
                    )
                } else null
            }
    }

    private fun findConflictingRemoteMutations(
        bookmarkKey: SyncBookmarkKey,
        localMutations: List<LocalModelMutation<SyncBookmark>>,
        remoteMutationsByKey: Map<SyncBookmarkKey, List<RemoteModelMutation<SyncBookmark>>>,
        remoteMutationsByRemoteID: Map<String, RemoteModelMutation<SyncBookmark>>
    ): List<RemoteModelMutation<SyncBookmark>> {
        val remoteMutationsById = localMutations.mapNotNull { it.remoteID }
            .mapNotNull { remoteMutationsByRemoteID[it] }

        return (remoteMutationsByKey[bookmarkKey].orEmpty() + remoteMutationsById)
            .distinct()
    }

    private fun extractConflictingIDs(resourceConflicts: List<ResourceConflict<SyncBookmark>>): Pair<Set<String>, Set<String>> {
        val conflictingRemoteIDs = resourceConflicts
            .flatMap { it.remoteMutations }
            .map { it.remoteID }
            .toSet()
            
        val conflictingLocalIDs = resourceConflicts
            .flatMap { it.localMutations }
            .map { it.localID }
            .toSet()
            
        return Pair(conflictingRemoteIDs, conflictingLocalIDs)
    }
    
    private fun buildResult(
        resourceConflicts: List<ResourceConflict<SyncBookmark>>,
        conflictingIDs: Pair<Set<String>, Set<String>>
    ): ConflictDetectionResult<SyncBookmark> {
        val (conflictingRemoteIDs, conflictingLocalIDs) = conflictingIDs
        
        val nonConflictingRemoteMutations = remoteMutations
            .filterNot { conflictingRemoteIDs.contains(it.remoteID) }
            
        val nonConflictingLocalMutations = localMutations
            .filterNot { conflictingLocalIDs.contains(it.localID) }
        
        return ConflictDetectionResult(
            conflicts = resourceConflicts,
            nonConflictingRemoteMutations = nonConflictingRemoteMutations,
            nonConflictingLocalMutations = nonConflictingLocalMutations
        )
    }
}

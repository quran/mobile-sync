package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.PageBookmark

class LocalMutationsPreprocessor {
    
    /**
     * Preprocesses local mutations and throws an error if illogical scenarios are detected.
     * 
     * Converts MODIFIED mutations to CREATED mutations.
     * 
     * @param localMutations List of local mutations to preprocess
     * @return List of local mutations if no illogical scenarios are detected
     * @throws IllegalArgumentException if illogical scenarios are detected
     */
    fun preprocess(localMutations: List<LocalModelMutation<PageBookmark>>): List<LocalModelMutation<PageBookmark>> {
        // Separate mutations by type
        val createdMutations = localMutations.filter { it.mutation == Mutation.CREATED }
        val deletedMutations = localMutations.filter { it.mutation == Mutation.DELETED }
        val modifiedMutations = localMutations.filter { it.mutation == Mutation.MODIFIED }
        
        val transformedModifiedMutations = modifiedMutations.map { mutation ->
            LocalModelMutation(
                model = mutation.model,
                remoteID = mutation.remoteID,
                localID = mutation.localID,
                mutation = Mutation.CREATED
            )
        }
        
        // Combine all mutations
        val allMutations = createdMutations + deletedMutations + transformedModifiedMutations
        
        // Group mutations by page
        val mutationsByPage = allMutations.groupBy { mutation ->
            mutation.model.page
        }
        
        val processedMutations = mutationsByPage.flatMap { (page, mutations) ->
            processPageMutations(page, mutations)
        }
        
        return processedMutations
    }
    
    private fun processPageMutations(
        page: Int, 
        mutations: List<LocalModelMutation<PageBookmark>>
    ): List<LocalModelMutation<PageBookmark>> {
        // Check for too many mutations
        if (mutations.size > 2) {
            throw IllegalArgumentException(
                "Illogical scenario detected: Page $page has ${mutations.size} mutations, " +
                "which exceeds logical limit of 2. Make sure to properly merge and aggregate" +
                "the local mutations to reflect the final propert state of the data" +
                "Mutations: ${mutations.map { "${it.mutation}(${it.localID})" }}"
            )
        }
        
        // Check for multiple deletions
        val deletions = mutations.filter { it.mutation == Mutation.DELETED }
        if (deletions.size > 1) {
            throw IllegalArgumentException(
                "Illogical scenario detected: Page $page has ${deletions.size} deletions, " +
                "which is not allowed. Mutations: ${mutations.map { "${it.mutation}(${it.localID})" }}"
            )
        }
        
        // Check that deletions have remote IDs
        deletions.forEach { deletion ->
            if (deletion.remoteID == null) {
                throw IllegalArgumentException(
                    "Illogical scenario detected: Page $page has deletion without remote ID, " +
                    "which is not allowed. Deletion must reference an existing remote resource. " +
                    "Mutation: ${deletion.mutation}(${deletion.localID})"
                )
            }
        }
        
        // Check for multiple creations
        val creations = mutations.filter { it.mutation == Mutation.CREATED }
        if (creations.size > 1) {
            throw IllegalArgumentException(
                "Illogical scenario detected: Page $page has ${creations.size} creations, " +
                "which is not allowed. Mutations: ${mutations.map { "${it.mutation}(${it.localID})" }}"
            )
        }
        
        // Handle creation followed by deletion (always invalid since deletions must have remote IDs)
        if (mutations.size == 2) {
            val first = mutations[0]
            val second = mutations[1]
            
            if (first.mutation == Mutation.CREATED && second.mutation == Mutation.DELETED) {
                // Invalid scenario - creation followed by deletion always indicates two bookmarks on same page
                throw IllegalArgumentException(
                    "Illogical scenario detected: Page $page has creation followed by deletion, " +
                    "indicating there were two bookmarks on the same page. " +
                    "Mutations: ${mutations.map { "${it.mutation}(${it.localID})" }}"
                )
            }
        }
        
        // All other scenarios are valid
        return mutations
    }
} 
package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation

/**
 * Preprocesses local mutations and throws an error if illogical scenarios are detected.
 * Currently detects the scenario where there are more than two mutations for the same page.
 */
class LocalMutationsPreprocessor<Model> {
    
    /**
     * Preprocesses local mutations and throws an error if illogical scenarios are detected.
     * 
     * @param localMutations List of local mutations to preprocess
     * @param pageExtractor Function to extract the page identifier from a model
     * @return List of local mutations if no illogical scenarios are detected
     * @throws IllegalArgumentException if illogical scenarios are detected
     */
    fun preprocess(
        localMutations: List<LocalModelMutation<Model>>,
        pageExtractor: (Model) -> Int
    ): List<LocalModelMutation<Model>> {
        // Group mutations by page
        val mutationsByPage = localMutations.groupBy { mutation ->
            pageExtractor(mutation.model)
        }
        
        // Check for illogical scenarios
        mutationsByPage.forEach { (page, mutations) ->
            if (mutations.size > 2) {
                throw IllegalArgumentException(
                    "Illogical scenario detected: Page $page has ${mutations.size} mutations, " +
                    "which exceeds the maximum allowed limit of 2. " +
                    "Mutations: ${mutations.map { "${it.mutation}(${it.localID})" }}"
                )
            }
        }
        
        // If no illogical scenarios detected, return the original list
        return localMutations
    }
} 
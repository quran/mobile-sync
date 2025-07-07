package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

data class PageBookmark(val id: String, val page: Int, val lastModified: Long)

interface LocalMutationsFetcher<Model> {
    // TODO: We must allow to input the local IDs, but we shouldn't let that leak into
    // the reported remoted mutations.
    suspend fun fetchLocalMutations(token: Long): List<LocalModelMutation<Model>>
}

interface ResultNotifier<Model> {
    suspend fun syncResult(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<Model>>,
        processedLocalMutations: List<LocalModelMutation<Model>>
    )
}

// This will be duplicated per each model type (or generalized), as defined by the BE.
class PageBookmarksSynchronizationConfigurations(
    // Probably, add configurations to select bookmark types to process.
    val localMutationsFetcher: LocalMutationsFetcher<PageBookmark>,
    val resultNotifier: ResultNotifier<PageBookmark>
)

interface SynchronizationClient {
    fun localDataUpdated()
    // Or move that to a builder.
    fun setBookmarksConfigurations(configurations: PageBookmarksSynchronizationConfigurations)
}
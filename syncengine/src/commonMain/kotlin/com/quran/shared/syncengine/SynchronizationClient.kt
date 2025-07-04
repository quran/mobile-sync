package com.quran.shared.syncengine

data class PageBookmark(val id: String, val page: Int, val lastModified: Long)

enum class Mutation {
    CREATED,
    DELETED,
    MODIFIED
}

class ModelMutation<Model>(val model: Model, val modelID: String, val mutation: Mutation)

interface LocalMutationsFetcher<Model> {
    // TODO: We must allow to input the local IDs, but we shouldn't let that leak into
    // the reported remoted mutations.
    suspend fun fetchLocalMutations(token: Long): List<ModelMutation<Model>>
}

interface ResultNotifier<Model> {
    suspend fun syncResult(
        newToken: Long,
        newRemoteMutations: List<ModelMutation<Model>>,
        processedLocalMutations: List<ModelMutation<Model>>
    )
}

// This will be duplicated per each model type (or generalized), as defined by the BE.
class PageBookmarksSynchronizationConfigurations(
    // Probably, add configurations to enable bookmark types.
    val localMutationsFetcher: suspend (Long) -> List<ModelMutation<PageBookmark>>,
    val resultNotifier: suspend (List<ModelMutation<PageBookmark>>, List<ModelMutation<PageBookmark>>, Long) -> Unit // Need to deliver back the local mutations
)

interface SynchronizationClient {
    fun localDataUpdated()
    // Or move that to a builder.
    fun setBookmarksConfigurations(configurations: PageBookmarksSynchronizationConfigurations)
}
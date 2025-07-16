package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation

data class PageBookmark(val id: String, val page: Int, val lastModified: Long)

interface LocalMutationsFetcher<Model> {
    suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<Model>>
}

interface ResultNotifier<Model> {
    suspend fun didSucceed(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<Model>>,
        processedLocalMutations: List<LocalModelMutation<Model>>
    )

    suspend fun didFail(message: String)
}

interface LocalModificationDateFetcher {
    suspend fun localLastModificationDate(): Long?
}

// This will be duplicated per each model type (or generalized), as defined by the BE.
class PageBookmarksSynchronizationConfigurations(
    // Probably, add configurations to select bookmark types to process.
    val localMutationsFetcher: LocalMutationsFetcher<PageBookmark>,
    val resultNotifier: ResultNotifier<PageBookmark>,
    val localModificationDateFetcher: LocalModificationDateFetcher
)

interface AuthenticationDataFetcher {
    suspend fun fetchAuthenticationHeaders(): Map<String, String>
}

interface SynchronizationClient {
    fun localDataUpdated()
    fun applicationStarted()
}

data class SynchronizationEnvironment(val endPointURL: String)

sealed class SynchronizationClientBuilder {
    companion object {
        fun build(
            environment: SynchronizationEnvironment,
            authFetcher: AuthenticationDataFetcher,
            bookmarksConfigurations: PageBookmarksSynchronizationConfigurations): SynchronizationClient {
            return SynchronizationClientImpl(
                environment,
                HttpClientFactory.createHttpClient(),
                bookmarksConfigurations,
                authFetcher
            )
        }
    }
}
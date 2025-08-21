package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.network.HttpClientFactory
import io.ktor.client.HttpClient
import kotlinx.datetime.Instant

data class PageBookmark(val id: String, val page: Int, val lastModified: Instant)

interface LocalDataFetcher<Model> {
    /**
     * Fetches local mutations that have occurred since the given timestamp.
     */
    suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<Model>>
    
    /**
     * Checks if the given remote IDs exist locally.
     * @param remoteIDs List of remote IDs to check
     * @return Map of remote ID to boolean indicating if it exists locally
     */
    suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean>
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
    val localDataFetcher: LocalDataFetcher<PageBookmark>,
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
            bookmarksConfigurations: PageBookmarksSynchronizationConfigurations,
            httpClient: HttpClient? = null
        ): SynchronizationClient {
            return SynchronizationClientImpl(
                environment,
                httpClient ?: HttpClientFactory.createHttpClient(),
                bookmarksConfigurations,
                authFetcher
            )
        }
    }
}
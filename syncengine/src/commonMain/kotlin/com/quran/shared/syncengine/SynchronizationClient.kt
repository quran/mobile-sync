@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.SyncCollection
import com.quran.shared.syncengine.model.SyncNote
import com.quran.shared.syncengine.network.HttpClientFactory
import io.ktor.client.HttpClient

interface LocalDataFetcher<Model> {
    /**
     * Fetches local mutations that have occurred since the given timestamp (epoch milliseconds).
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
    /**
     * Returns the last local modification timestamp in epoch milliseconds.
     */
    suspend fun localLastModificationDate(): Long?
}

// This will be duplicated per each model type (or generalized), as defined by the BE.
class BookmarksSynchronizationConfigurations(
    // Probably, add configurations to select bookmark types to process.
    val localDataFetcher: LocalDataFetcher<SyncBookmark>,
    val resultNotifier: ResultNotifier<SyncBookmark>,
    val localModificationDateFetcher: LocalModificationDateFetcher
)

class CollectionsSynchronizationConfigurations(
    val localDataFetcher: LocalDataFetcher<SyncCollection>,
    val resultNotifier: ResultNotifier<SyncCollection>,
    val localModificationDateFetcher: LocalModificationDateFetcher
)

class CollectionBookmarksSynchronizationConfigurations(
    val localDataFetcher: LocalDataFetcher<SyncCollectionBookmark>,
    val resultNotifier: ResultNotifier<SyncCollectionBookmark>,
    val localModificationDateFetcher: LocalModificationDateFetcher
)

class NotesSynchronizationConfigurations(
    val localDataFetcher: LocalDataFetcher<SyncNote>,
    val resultNotifier: ResultNotifier<SyncNote>,
    val localModificationDateFetcher: LocalModificationDateFetcher
)

interface AuthenticationDataFetcher {
    suspend fun fetchAuthenticationHeaders(): Map<String, String>
    fun isLoggedIn(): Boolean
}

interface SynchronizationClient {
    fun localDataUpdated()
    fun applicationStarted()
    fun triggerSyncImmediately()
    fun cancelSyncing()
}

data class SynchronizationEnvironment(val endPointURL: String)

object SynchronizationClientBuilder {
    fun build(
        environment: SynchronizationEnvironment,
        authFetcher: AuthenticationDataFetcher,
        bookmarksConfigurations: BookmarksSynchronizationConfigurations,
        collectionsConfigurations: CollectionsSynchronizationConfigurations? = null,
        collectionBookmarksConfigurations: CollectionBookmarksSynchronizationConfigurations? = null,
        notesConfigurations: NotesSynchronizationConfigurations? = null,
        httpClient: HttpClient? = null
    ): SynchronizationClient {
        val adapters = buildList {
            add(BookmarksSyncAdapter(bookmarksConfigurations))
            collectionsConfigurations?.let { add(CollectionsSyncAdapter(it)) }
            collectionBookmarksConfigurations?.let { add(CollectionBookmarksSyncAdapter(it)) }
            notesConfigurations?.let { add(NotesSyncAdapter(it)) }
        }
        return SynchronizationClientImpl(
            environment,
            httpClient ?: HttpClientFactory.createHttpClient(),
            adapters,
            authFetcher
        )
    }
}

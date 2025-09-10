@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.pipeline

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.repository.PageBookmarksSynchronizationRepository
import com.quran.shared.syncengine.AuthenticationDataFetcher
import com.quran.shared.syncengine.LocalModificationDateFetcher
import com.quran.shared.syncengine.LocalDataFetcher
import com.quran.shared.syncengine.PageBookmark
import com.quran.shared.syncengine.PageBookmarksSynchronizationConfigurations
import com.quran.shared.syncengine.ResultNotifier
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationClientBuilder
import com.quran.shared.syncengine.SynchronizationEnvironment

interface SyncEngineCallback {
    fun synchronizationDone(newLastModificationDate: Long)
    fun encounteredError(errorMsg: String)
}

public class SyncEnginePipeline(
    val bookmarksRepository: PageBookmarksSynchronizationRepository
) {
    private lateinit var syncClient: SynchronizationClient

    fun setup(
        environment: SynchronizationEnvironment,
        localModificationDateFetcher: LocalModificationDateFetcher,
        authenticationDataFetcher: AuthenticationDataFetcher,
        callback: SyncEngineCallback
    ): SynchronizationClient {

        val bookmarksConf = PageBookmarksSynchronizationConfigurations(
            localModificationDateFetcher = localModificationDateFetcher,
            resultNotifier = ResultReceiver(bookmarksRepository, callback),
            localDataFetcher = RepositoryDataFetcher(bookmarksRepository)
        )
        val syncClient = SynchronizationClientBuilder.build(
            environment = environment,
            authFetcher = authenticationDataFetcher,
            bookmarksConfigurations = bookmarksConf
        )

        this.syncClient = syncClient

        return syncClient
    }

    fun startListening() {
        // TODO:
    }
}

private class RepositoryDataFetcher(val bookmarksRepository: PageBookmarksSynchronizationRepository): LocalDataFetcher<PageBookmark> {

    override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<PageBookmark>> {
        return bookmarksRepository.fetchMutatedBookmarks().map { repoMutation ->
            LocalModelMutation(
                model = repoMutation.model.toSyncEngine(),
                remoteID = repoMutation.remoteID,
                localID = repoMutation.localID,
                mutation = repoMutation.mutation
            )
        }
    }

    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
        return bookmarksRepository.remoteResourcesExist(remoteIDs)
    }
}

private class ResultReceiver(
    val repository: PageBookmarksSynchronizationRepository,
    val callback: SyncEngineCallback): ResultNotifier<PageBookmark> {

    override suspend fun didFail(message: String) {
        callback.encounteredError(message)
    }

    override suspend fun didSucceed(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<PageBookmark>>,
        processedLocalMutations: List<LocalModelMutation<PageBookmark>>
    ) {
        val mappedRemotes = newRemoteMutations.map { remoteMutation ->
            RemoteModelMutation(
                model = remoteMutation.model.toPersistence(),
                remoteID = remoteMutation.remoteID,
                mutation = remoteMutation.mutation
            )
        }
        val mappedLocals = processedLocalMutations.map { localMutation ->
            LocalModelMutation(
                model = localMutation.model.toPersistence(),
                localID = localMutation.localID,
                remoteID = localMutation.remoteID,
                mutation = localMutation.mutation
            )
        }

        Logger.i { "Persisting ${mappedRemotes.count()} remote updates, and clearing ${mappedLocals.count()} local updates." }
        
        repository.applyRemoteChanges(mappedRemotes, mappedLocals)
        callback.synchronizationDone(newToken)
    }
}

private fun com.quran.shared.persistence.model.PageBookmark.toSyncEngine(): PageBookmark {
    if (localId == null) {
        throw RuntimeException("Transforming a Persistence's PageBookmark without a local ID.")
    }
    return PageBookmark(
        page = this.page,
        id = this.localId!!,
        lastModified = this.lastUpdated
        )
}

private fun PageBookmark.toPersistence(): com.quran.shared.persistence.model.PageBookmark {
    return com.quran.shared.persistence.model.PageBookmark(
        page = this.page,
        lastUpdated = this.lastModified,
        localId = this.id
    )
}
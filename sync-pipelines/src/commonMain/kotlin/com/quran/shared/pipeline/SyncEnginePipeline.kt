package com.quran.shared.pipeline

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.repository.PageBookmarksSynchronizationRepository
import com.quran.shared.syncengine.AuthenticationDataFetcher
import com.quran.shared.syncengine.LocalModificationDateFetcher
import com.quran.shared.syncengine.LocalMutationsFetcher
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
    var syncClient: SynchronizationClient? = null

    fun setup(
        environment: SynchronizationEnvironment,
        localModificationDateFetcher: LocalModificationDateFetcher,
        authenticationDataFetcher: AuthenticationDataFetcher,
        callback: SyncEngineCallback
    ): SynchronizationClient {

        val bookmarksConf = PageBookmarksSynchronizationConfigurations(
            localModificationDateFetcher = localModificationDateFetcher,
            resultNotifier = ResultReceiver(bookmarksRepository, callback),
            localMutationsFetcher = RepositoryReader(bookmarksRepository)
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

    }
}

private class RepositoryReader(val bookmarksRepository: PageBookmarksSynchronizationRepository): LocalMutationsFetcher<PageBookmark> {

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
        repository.applyRemoteChanges(mappedRemotes, mappedLocals)
        callback.synchronizationDone(newToken)
    }
}

private fun com.quran.shared.persistence.model.PageBookmark.toSyncEngine(): PageBookmark {
    return PageBookmark(
        page = this.page,
        id = this.remoteId ?: "", // Makes no sense
        lastModified = this.lastUpdated
        )
}

private  fun PageBookmark.toPersistence(): com.quran.shared.persistence.model.PageBookmark {
    return com.quran.shared.persistence.model.PageBookmark(
        page = this.page,
        lastUpdated = this.lastModified,
        remoteId = this.id
    )
}
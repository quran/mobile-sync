package com.quran.shared.pipeline

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncengine.AuthenticationDataFetcher
import com.quran.shared.syncengine.LocalDataFetcher
import com.quran.shared.syncengine.LocalModificationDateFetcher
import com.quran.shared.syncengine.BookmarksSynchronizationConfigurations
import com.quran.shared.syncengine.ResultNotifier
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationClientBuilder
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.quran.shared.syncengine.model.SyncBookmark

interface SyncEngineCallback {
    fun synchronizationDone(newLastModificationDate: Long)
    fun encounteredError(errorMsg: String)
}

public class SyncEnginePipeline(
    val bookmarksRepository: BookmarksSynchronizationRepository
) {
    private lateinit var syncClient: SynchronizationClient

    fun setup(
        environment: SynchronizationEnvironment,
        localModificationDateFetcher: LocalModificationDateFetcher,
        authenticationDataFetcher: AuthenticationDataFetcher,
        callback: SyncEngineCallback
    ): SynchronizationClient {

        val bookmarksConf = BookmarksSynchronizationConfigurations(
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

private class RepositoryDataFetcher(val bookmarksRepository: BookmarksSynchronizationRepository): LocalDataFetcher<SyncBookmark> {

    override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncBookmark>> {
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
    val repository: BookmarksSynchronizationRepository,
    val callback: SyncEngineCallback): ResultNotifier<SyncBookmark> {

    override suspend fun didFail(message: String) {
        callback.encounteredError(message)
    }

    override suspend fun didSucceed(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<SyncBookmark>>,
        processedLocalMutations: List<LocalModelMutation<SyncBookmark>>
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

private fun Bookmark.toSyncEngine(): SyncBookmark {
    return when (this) {
        is Bookmark.PageBookmark -> {
            val localId = this.localId
                ?: throw RuntimeException("Transforming a persistence PageBookmark without a local ID.")
            SyncBookmark.PageBookmark(
                page = this.page,
                id = localId,
                lastModified = this.lastUpdated.fromPlatform()
            )
        }
        is Bookmark.AyahBookmark -> {
            val localId = this.localId
                ?: throw RuntimeException("Transforming a persistence AyahBookmark without a local ID.")
            SyncBookmark.AyahBookmark(
                id = localId,
                sura = this.sura,
                ayah = this.ayah,
                lastModified = this.lastUpdated.fromPlatform()
            )
        }
    }
}

private fun SyncBookmark.toPersistence(): Bookmark {
    return when (this) {
        is SyncBookmark.PageBookmark ->
            Bookmark.PageBookmark(
                page = this.page,
                lastUpdated = this.lastModified.toPlatform(),
                localId = this.id
            )
        is SyncBookmark.AyahBookmark ->
            Bookmark.AyahBookmark(
                sura = this.sura,
                ayah = this.ayah,
                lastUpdated = this.lastModified.toPlatform(),
                localId = this.id
            )
    }
}

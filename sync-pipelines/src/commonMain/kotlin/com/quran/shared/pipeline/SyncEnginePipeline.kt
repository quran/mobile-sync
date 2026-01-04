package com.quran.shared.pipeline

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.Collection as PersistenceCollection
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsSynchronizationRepository
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncengine.AuthenticationDataFetcher
import com.quran.shared.syncengine.LocalDataFetcher
import com.quran.shared.syncengine.LocalModificationDateFetcher
import com.quran.shared.syncengine.BookmarksSynchronizationConfigurations
import com.quran.shared.syncengine.CollectionsSynchronizationConfigurations
import com.quran.shared.syncengine.ResultNotifier
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationClientBuilder
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.SyncCollection

interface SyncEngineCallback {
    fun synchronizationDone(newLastModificationDate: Long)
    fun encounteredError(errorMsg: String)
}

public class SyncEnginePipeline(
    val bookmarksRepository: BookmarksSynchronizationRepository,
    val collectionsRepository: CollectionsSynchronizationRepository
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
        val collectionsConf = CollectionsSynchronizationConfigurations(
            localModificationDateFetcher = localModificationDateFetcher,
            resultNotifier = CollectionsResultReceiver(collectionsRepository, callback),
            localDataFetcher = CollectionsRepositoryDataFetcher(collectionsRepository)
        )
        val syncClient = SynchronizationClientBuilder.build(
            environment = environment,
            authFetcher = authenticationDataFetcher,
            bookmarksConfigurations = bookmarksConf,
            collectionsConfigurations = collectionsConf
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

private class CollectionsRepositoryDataFetcher(
    val collectionsRepository: CollectionsSynchronizationRepository
) : LocalDataFetcher<SyncCollection> {

    override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncCollection>> {
        return collectionsRepository.fetchMutatedCollections().map { repoMutation ->
            LocalModelMutation(
                model = repoMutation.model.toSyncEngine(),
                remoteID = repoMutation.remoteID,
                localID = repoMutation.localID,
                mutation = repoMutation.mutation
            )
        }
    }

    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
        return collectionsRepository.remoteResourcesExist(remoteIDs)
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
                model = remoteMutation.model.toRemoteInput(),
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

private class CollectionsResultReceiver(
    val repository: CollectionsSynchronizationRepository,
    val callback: SyncEngineCallback
) : ResultNotifier<SyncCollection> {

    override suspend fun didFail(message: String) {
        callback.encounteredError(message)
    }

    override suspend fun didSucceed(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<SyncCollection>>,
        processedLocalMutations: List<LocalModelMutation<SyncCollection>>
    ) {
        val mappedRemotes = newRemoteMutations.map { remoteMutation ->
            RemoteModelMutation(
                model = remoteMutation.model.toRemoteInput(),
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

        Logger.i {
            "Persisting ${mappedRemotes.count()} collection remote updates, " +
                "and clearing ${mappedLocals.count()} local updates."
        }

        repository.applyRemoteChanges(mappedRemotes, mappedLocals)
        callback.synchronizationDone(newToken)
    }
}

private fun Bookmark.toSyncEngine(): SyncBookmark {
    return when (this) {
        is Bookmark.PageBookmark -> {
            SyncBookmark.PageBookmark(
                page = this.page,
                id = this.localId,
                lastModified = this.lastUpdated.fromPlatform()
            )
        }
        is Bookmark.AyahBookmark -> {
            SyncBookmark.AyahBookmark(
                id = this.localId,
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

private fun PersistenceCollection.toSyncEngine(): SyncCollection {
    return SyncCollection(
        id = this.localId,
        name = this.name,
        lastModified = this.lastUpdated.fromPlatform()
    )
}

private fun SyncCollection.toPersistence(): PersistenceCollection {
    return PersistenceCollection(
        name = requireNotNull(this.name) { "Transforming a collection without a name." },
        lastUpdated = this.lastModified.toPlatform(),
        localId = this.id
    )
}

private fun SyncBookmark.toRemoteInput(): RemoteBookmark {
    return when (this) {
        is SyncBookmark.PageBookmark ->
            RemoteBookmark.Page(
                page = this.page,
                lastUpdated = this.lastModified.toPlatform()
            )
        is SyncBookmark.AyahBookmark ->
            RemoteBookmark.Ayah(
                sura = this.sura,
                ayah = this.ayah,
                lastUpdated = this.lastModified.toPlatform()
            )
    }
}

private fun SyncCollection.toRemoteInput(): RemoteCollection {
    return RemoteCollection(
        name = this.name,
        lastUpdated = this.lastModified.toPlatform()
    )
}

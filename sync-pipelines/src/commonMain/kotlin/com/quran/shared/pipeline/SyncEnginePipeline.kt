package com.quran.shared.pipeline

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.input.RemoteCollectionBookmark
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.input.RemoteNote
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.CollectionBookmark
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.model.Collection as PersistenceCollection
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsSynchronizationRepository
import com.quran.shared.persistence.repository.note.repository.NotesSynchronizationRepository
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncengine.AuthenticationDataFetcher
import com.quran.shared.syncengine.LocalDataFetcher
import com.quran.shared.syncengine.LocalModificationDateFetcher
import com.quran.shared.syncengine.BookmarksSynchronizationConfigurations
import com.quran.shared.syncengine.CollectionBookmarksSynchronizationConfigurations
import com.quran.shared.syncengine.CollectionsSynchronizationConfigurations
import com.quran.shared.syncengine.NotesSynchronizationConfigurations
import com.quran.shared.syncengine.ResultNotifier
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationClientBuilder
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.quran.shared.syncengine.model.NoteAyah
import com.quran.shared.syncengine.model.NoteRange
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.SyncCollection
import com.quran.shared.syncengine.model.SyncNote

interface SyncEngineCallback {
    fun synchronizationDone(newLastModificationDate: Long)
    fun encounteredError(errorMsg: String)
}

public class SyncEnginePipeline(
    val bookmarksRepository: BookmarksSynchronizationRepository,
    val collectionsRepository: CollectionsSynchronizationRepository,
    val collectionBookmarksRepository: CollectionBookmarksSynchronizationRepository? = null,
    val notesRepository: NotesSynchronizationRepository? = null
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
        val collectionBookmarksConf = collectionBookmarksRepository?.let { repository ->
            CollectionBookmarksSynchronizationConfigurations(
                localModificationDateFetcher = localModificationDateFetcher,
                resultNotifier = CollectionBookmarksResultReceiver(repository, callback),
                localDataFetcher = CollectionBookmarksRepositoryDataFetcher(repository, bookmarksRepository)
            )
        }
        val notesConf = notesRepository?.let { repository ->
            NotesSynchronizationConfigurations(
                localModificationDateFetcher = localModificationDateFetcher,
                resultNotifier = NotesResultReceiver(repository, callback),
                localDataFetcher = NotesRepositoryDataFetcher(repository)
            )
        }
        val syncClient = SynchronizationClientBuilder.build(
            environment = environment,
            authFetcher = authenticationDataFetcher,
            bookmarksConfigurations = bookmarksConf,
            collectionsConfigurations = collectionsConf,
            collectionBookmarksConfigurations = collectionBookmarksConf,
            notesConfigurations = notesConf
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

    override suspend fun fetchLocalModel(remoteId: String): SyncBookmark? {
        return bookmarksRepository.fetchBookmarkByRemoteId(remoteId)?.toSyncEngine()
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

    override suspend fun fetchLocalModel(remoteId: String): SyncCollection? {
        return null
    }
}

private class CollectionBookmarksRepositoryDataFetcher(
    val collectionBookmarksRepository: CollectionBookmarksSynchronizationRepository,
    val bookmarksRepository: BookmarksSynchronizationRepository
) : LocalDataFetcher<SyncCollectionBookmark> {

    override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncCollectionBookmark>> {
        return collectionBookmarksRepository.fetchMutatedCollectionBookmarks().map { repoMutation ->
            LocalModelMutation(
                model = repoMutation.model.toSyncEngine(),
                remoteID = repoMutation.remoteID,
                localID = repoMutation.localID,
                mutation = repoMutation.mutation
            )
        }
    }

    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
        return collectionBookmarksRepository.remoteResourcesExist(remoteIDs)
    }

    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? {
        val bookmark = bookmarksRepository.fetchBookmarkByRemoteId(remoteId) ?: return null
        return when (bookmark) {
            is Bookmark.PageBookmark -> SyncCollectionBookmark.PageBookmark(
                collectionId = "", // Not used for this fetch
                page = bookmark.page,
                lastModified = bookmark.lastUpdated.fromPlatform(),
                bookmarkId = remoteId
            )
            is Bookmark.AyahBookmark -> SyncCollectionBookmark.AyahBookmark(
                collectionId = "", // Not used for this fetch
                sura = bookmark.sura,
                ayah = bookmark.ayah,
                lastModified = bookmark.lastUpdated.fromPlatform(),
                bookmarkId = remoteId
            )
        }
    }
}

private class NotesRepositoryDataFetcher(
    val notesRepository: NotesSynchronizationRepository
) : LocalDataFetcher<SyncNote> {
    private val logger = Logger.withTag("NotesRepositoryDataFetcher")

    override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncNote>> {
        return notesRepository.fetchMutatedNotes(lastModified).mapNotNull { repoMutation ->
            val syncNote = repoMutation.model.toSyncEngine()
            if (syncNote == null) {
                logger.w { "Skipping note mutation with invalid ayah range: localId=${repoMutation.localID}" }
                null
            } else {
                LocalModelMutation(
                    model = syncNote,
                    remoteID = repoMutation.remoteID,
                    localID = repoMutation.localID,
                    mutation = repoMutation.mutation
                )
            }
        }
    }

    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
        return notesRepository.remoteResourcesExist(remoteIDs)
    }

    override suspend fun fetchLocalModel(remoteId: String): SyncNote? {
        return null
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

private class CollectionBookmarksResultReceiver(
    val repository: CollectionBookmarksSynchronizationRepository,
    val callback: SyncEngineCallback
) : ResultNotifier<SyncCollectionBookmark> {

    override suspend fun didFail(message: String) {
        callback.encounteredError(message)
    }

    override suspend fun didSucceed(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
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
                model = localMutation.model.toPersistence(localMutation.localID),
                localID = localMutation.localID,
                remoteID = localMutation.remoteID,
                mutation = localMutation.mutation
            )
        }

        Logger.i {
            "Persisting ${mappedRemotes.count()} collection bookmark remote updates, " +
                "and clearing ${mappedLocals.count()} local updates."
        }

        repository.applyRemoteChanges(mappedRemotes, mappedLocals)
        callback.synchronizationDone(newToken)
    }
}

private class NotesResultReceiver(
    val repository: NotesSynchronizationRepository,
    val callback: SyncEngineCallback
) : ResultNotifier<SyncNote> {
    private val logger = Logger.withTag("NotesResultReceiver")

    override suspend fun didFail(message: String) {
        callback.encounteredError(message)
    }

    override suspend fun didSucceed(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<SyncNote>>,
        processedLocalMutations: List<LocalModelMutation<SyncNote>>
    ) {
        val mappedRemotes = newRemoteMutations.mapNotNull { remoteMutation ->
            val remoteNote = when (remoteMutation.mutation) {
                Mutation.DELETED -> RemoteNote(
                    body = null,
                    startAyahId = null,
                    endAyahId = null,
                    lastUpdated = remoteMutation.model.lastModified.toPlatform()
                )
                Mutation.CREATED, Mutation.MODIFIED -> remoteMutation.model.toRemoteInput()
            }

            if (remoteNote == null) {
                logger.w { "Skipping remote note mutation without valid ranges: remoteId=${remoteMutation.remoteID}" }
                null
            } else {
                RemoteModelMutation(
                    model = remoteNote,
                    remoteID = remoteMutation.remoteID,
                    mutation = remoteMutation.mutation
                )
            }
        }

        val mappedLocals = processedLocalMutations.mapNotNull { localMutation ->
            val persistenceNote = localMutation.model.toPersistence(localMutation.localID)
            if (persistenceNote == null) {
                logger.w { "Skipping local note mutation without valid ranges: localId=${localMutation.localID}" }
                null
            } else {
                LocalModelMutation(
                    model = persistenceNote,
                    localID = localMutation.localID,
                    remoteID = localMutation.remoteID,
                    mutation = localMutation.mutation
                )
            }
        }

        Logger.i {
            "Persisting ${mappedRemotes.count()} note remote updates, " +
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

private fun CollectionBookmark.toSyncEngine(): SyncCollectionBookmark {
    val collectionId = requireNotNull(collectionRemoteId) { "Collection remote ID is required for sync." }
    return when (this) {
        is CollectionBookmark.PageBookmark ->
            SyncCollectionBookmark.PageBookmark(
                collectionId = collectionId,
                page = this.page,
                lastModified = this.lastUpdated.fromPlatform()
            )
        is CollectionBookmark.AyahBookmark ->
            SyncCollectionBookmark.AyahBookmark(
                collectionId = collectionId,
                sura = this.sura,
                ayah = this.ayah,
                lastModified = this.lastUpdated.fromPlatform()
            )
    }
}

private fun SyncCollectionBookmark.toPersistence(localId: String): CollectionBookmark {
    val updatedAt = lastModified.toPlatform()
    return when (this) {
        is SyncCollectionBookmark.PageBookmark ->
            CollectionBookmark.PageBookmark(
                collectionLocalId = "",
                collectionRemoteId = collectionId,
                bookmarkLocalId = "",
                page = page,
                lastUpdated = updatedAt,
                localId = localId
            )
        is SyncCollectionBookmark.AyahBookmark ->
            CollectionBookmark.AyahBookmark(
                collectionLocalId = "",
                collectionRemoteId = collectionId,
                bookmarkLocalId = "",
                sura = sura,
                ayah = ayah,
                lastUpdated = updatedAt,
                localId = localId
            )
    }
}

private fun SyncCollectionBookmark.toRemoteInput(): RemoteCollectionBookmark {
    val updatedAt = lastModified.toPlatform()
    return when (this) {
        is SyncCollectionBookmark.PageBookmark ->
            RemoteCollectionBookmark.Page(
                collectionId = collectionId,
                page = page,
                lastUpdated = updatedAt,
                bookmarkId = bookmarkId
            )
        is SyncCollectionBookmark.AyahBookmark ->
            RemoteCollectionBookmark.Ayah(
                collectionId = collectionId,
                sura = sura,
                ayah = ayah,
                lastUpdated = updatedAt,
                bookmarkId = bookmarkId
            )
    }
}

private fun Note.toSyncEngine(): SyncNote? {
    val start = ayahIdToSuraAyah(startAyahId) ?: return null
    val end = ayahIdToSuraAyah(endAyahId) ?: return null
    return SyncNote(
        id = localId,
        body = body,
        ranges = listOf(NoteRange(start = start, end = end)),
        lastModified = lastUpdated.fromPlatform()
    )
}

private fun SyncNote.toPersistence(localId: String): Note? {
    val range = primaryRangeOrNull() ?: return null
    val startId = suraAyahToAyahId(range.start.sura, range.start.ayah) ?: return null
    val endId = suraAyahToAyahId(range.end.sura, range.end.ayah) ?: return null
    val noteBody = requireNotNull(body) { "Transforming a note without a body." }
    return Note(
        body = noteBody,
        startAyahId = startId,
        endAyahId = endId,
        lastUpdated = lastModified.toPlatform(),
        localId = localId
    )
}

private fun SyncNote.toRemoteInput(): RemoteNote? {
    val range = primaryRangeOrNull() ?: return null
    val startId = suraAyahToAyahId(range.start.sura, range.start.ayah) ?: return null
    val endId = suraAyahToAyahId(range.end.sura, range.end.ayah) ?: return null
    return RemoteNote(
        body = body,
        startAyahId = startId,
        endAyahId = endId,
        lastUpdated = lastModified.toPlatform()
    )
}

private val notesRangeLogger = Logger.withTag("NotesRangeMapper")

private fun SyncNote.primaryRangeOrNull(): NoteRange? {
    if (ranges.isEmpty()) {
        return null
    }
    if (ranges.size > 1) {
        notesRangeLogger.w { "Note contains ${ranges.size} ranges; only the first will be synced. noteId=$id" }
    }
    return ranges.first()
}

private fun suraAyahToAyahId(sura: Int, ayah: Int): Long? {
    if (sura !in 1..QuranData.suraAyahCounts.size) {
        return null
    }
    val count = QuranData.suraAyahCounts[sura - 1]
    if (ayah !in 1..count) {
        return null
    }
    val offset = QuranData.suraAyahOffsets[sura - 1]
    return (offset + ayah).toLong()
}

private fun ayahIdToSuraAyah(ayahId: Long): NoteAyah? {
    if (ayahId <= 0) {
        return null
    }
    var remaining = ayahId.toInt()
    for (index in QuranData.suraAyahCounts.indices) {
        val count = QuranData.suraAyahCounts[index]
        if (remaining <= count) {
            return NoteAyah(sura = index + 1, ayah = remaining)
        }
        remaining -= count
    }
    return null
}

package com.quran.shared.pipeline

import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.input.RemoteCollectionBookmark
import com.quran.shared.persistence.input.RemoteNote
import com.quran.shared.persistence.input.RemoteReadingSession
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.model.Collection as PersistenceCollection
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsSynchronizationRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.note.repository.NotesSynchronizationRepository
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepository
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsSynchronizationRepository
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncengine.AuthenticationDataFetcher
import com.quran.shared.syncengine.BookmarksSynchronizationConfigurations
import com.quran.shared.syncengine.CollectionBookmarksSynchronizationConfigurations
import com.quran.shared.syncengine.CollectionsSynchronizationConfigurations
import com.quran.shared.syncengine.LocalDataFetcher
import com.quran.shared.syncengine.LocalModificationDateFetcher
import com.quran.shared.syncengine.NotesSynchronizationConfigurations
import com.quran.shared.syncengine.ReadingSessionsSynchronizationConfigurations
import com.quran.shared.syncengine.ResultNotifier
import com.quran.shared.syncengine.SyncCompletionFinalizer
import com.quran.shared.syncengine.SyncLifecycleGate
import com.quran.shared.syncengine.SynchronizationClient
import com.quran.shared.syncengine.SynchronizationClientBuilder
import com.quran.shared.syncengine.SynchronizationEnvironment
import com.quran.shared.syncengine.checkCurrentSyncWriteBoundary
import com.quran.shared.syncengine.model.NoteAyah
import com.quran.shared.syncengine.model.NoteRange
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.SyncCollection
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.SyncNote
import com.quran.shared.syncengine.model.SyncReadingSession
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
interface SyncEngineCallback {
    suspend fun synchronizationDone(newLastModificationDate: Long)
    suspend fun encounteredError(errorMsg: String)
}

@HiddenFromObjC
fun interface SyncWriteBoundaryGuard {
    suspend fun checkWriteBoundary()
}

private val NoOpSyncWriteBoundaryGuard = SyncWriteBoundaryGuard {}

internal val CurrentSyncWriteBoundaryGuard = SyncWriteBoundaryGuard {
    checkCurrentSyncWriteBoundary()
}

private fun SyncWriteBoundaryGuard.toPersistenceWriteBoundaryGuard(): PersistenceWriteBoundaryGuard =
    PersistenceWriteBoundaryGuard {
        checkWriteBoundary()
    }

private inline fun <Source, Target> LocalModelMutation<Source>.mapModel(
    transform: (LocalModelMutation<Source>) -> Target
): LocalModelMutation<Target> =
    LocalModelMutation(
        model = transform(this),
        remoteID = remoteID,
        localID = localID,
        mutation = mutation,
        ack = ack
    )

private inline fun <Source, Target> RemoteModelMutation<Source>.mapModel(
    transform: (RemoteModelMutation<Source>) -> Target
): RemoteModelMutation<Target> =
    RemoteModelMutation(
        model = transform(this),
        remoteID = remoteID,
        mutation = mutation,
        ack = ack
    )

@Inject
@SingleIn(AppScope::class)
@HiddenFromObjC
class SyncEnginePipeline(
    val bookmarksRepository: BookmarksSynchronizationRepository,
    val readingBookmarksRepository: ReadingBookmarksRepository,
    val collectionsRepository: CollectionsSynchronizationRepository,
    val collectionBookmarksRepository: CollectionBookmarksSynchronizationRepository,
    val notesRepository: NotesSynchronizationRepository,
    val readingSessionsRepository: ReadingSessionsSynchronizationRepository
) {
    private lateinit var syncClient: SynchronizationClient

    fun setup(
        environment: SynchronizationEnvironment,
        localModificationDateFetcher: LocalModificationDateFetcher,
        authenticationDataFetcher: AuthenticationDataFetcher,
        syncLifecycleGate: SyncLifecycleGate,
        callback: SyncEngineCallback
    ): SynchronizationClient {
        val writeBoundaryGuard = CurrentSyncWriteBoundaryGuard

        val bookmarksConf = BookmarksSynchronizationConfigurations(
            localModificationDateFetcher = localModificationDateFetcher,
            resultNotifier = ResultReceiver(bookmarksRepository, callback, writeBoundaryGuard),
            localDataFetcher = RepositoryDataFetcher(bookmarksRepository)
        )
        val collectionsConf = CollectionsSynchronizationConfigurations(
            localModificationDateFetcher = localModificationDateFetcher,
            resultNotifier = CollectionsResultReceiver(collectionsRepository, callback, writeBoundaryGuard),
            localDataFetcher = CollectionsRepositoryDataFetcher(collectionsRepository)
        )
        val collectionBookmarksConf = CollectionBookmarksSynchronizationConfigurations(
            localModificationDateFetcher = localModificationDateFetcher,
            resultNotifier = CollectionBookmarksResultReceiver(
                collectionBookmarksRepository,
                callback,
                writeBoundaryGuard
            ),
            localDataFetcher = CollectionBookmarksRepositoryDataFetcher(collectionBookmarksRepository, bookmarksRepository)
        )
        val notesConf = NotesSynchronizationConfigurations(
            localModificationDateFetcher = localModificationDateFetcher,
            resultNotifier = NotesResultReceiver(notesRepository, callback, writeBoundaryGuard),
            localDataFetcher = NotesRepositoryDataFetcher(notesRepository)
        )
        val readingSessionsConf = ReadingSessionsSynchronizationConfigurations(
            localModificationDateFetcher = localModificationDateFetcher,
            resultNotifier = ReadingSessionsResultReceiver(
                repository = readingSessionsRepository,
                callback = callback,
                writeBoundaryGuard = writeBoundaryGuard
            ),
            localDataFetcher = ReadingSessionsRepositoryDataFetcher(readingSessionsRepository)
        )
        val syncClient = SynchronizationClientBuilder.build(
            environment = environment,
            authFetcher = authenticationDataFetcher,
            bookmarksConfigurations = bookmarksConf,
            collectionsConfigurations = collectionsConf,
            collectionBookmarksConfigurations = collectionBookmarksConf,
            notesConfigurations = notesConf,
            readingSessionsConfigurations = readingSessionsConf,
            syncLifecycleGate = syncLifecycleGate,
            syncCompletionFinalizer = SyncCompletionFinalizer { token ->
                callback.synchronizationDone(token)
            }
        )

        this.syncClient = syncClient

        return syncClient
    }

    fun startListening() {
        // TODO:
    }
}

private class RepositoryDataFetcher(
    val bookmarksRepository: BookmarksSynchronizationRepository
) : LocalDataFetcher<SyncBookmark> {

    override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncBookmark>> {
        return bookmarksRepository.fetchMutatedBookmarks().map { repoMutation ->
            repoMutation.mapModel { it.model.toSyncEngine(it.localID) }
        }
            .sortedByDescending { it.model.lastModified.toEpochMilliseconds() }
    }

    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
        return bookmarksRepository.remoteResourcesExist(remoteIDs)
    }

    override suspend fun fetchLocalModel(remoteId: String): SyncBookmark? {
        return bookmarksRepository.fetchBookmarkByRemoteId(remoteId)?.toSyncEngine(remoteId)
    }

    override suspend fun markLocalMutationsInFlight(
        localMutations: List<LocalModelMutation<SyncBookmark>>
    ): List<LocalMutationAck> =
        bookmarksRepository.markMutatedBookmarksInFlight(localMutations.mapNotNull { it.ack })

    override suspend fun rollbackLocalMutationsInFlight(acks: List<LocalMutationAck>) {
        bookmarksRepository.rollbackMutatedBookmarksInFlight(acks)
    }
}

private class CollectionsRepositoryDataFetcher(
    val collectionsRepository: CollectionsSynchronizationRepository
) : LocalDataFetcher<SyncCollection> {

    override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncCollection>> {
        return collectionsRepository.fetchMutatedCollections().map { repoMutation ->
            repoMutation.mapModel { it.model.toSyncEngine() }
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
            repoMutation.mapModel { it.model.toSyncEngine() }
        }
    }

    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
        return collectionBookmarksRepository.remoteResourcesExist(remoteIDs)
    }

    override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? {
        collectionBookmarksRepository.fetchCollectionBookmarkByRemoteId(remoteId)?.let { collectionBookmark ->
            return collectionBookmark.toSyncEngine()
        }
        val bookmark = bookmarksRepository.fetchBookmarkByRemoteId(remoteId) ?: return null
        return when (bookmark) {
            is RemoteBookmark.Ayah -> SyncCollectionBookmark.AyahBookmark(
                collectionId = "", // Not used for this fetch
                sura = bookmark.sura,
                ayah = bookmark.ayah,
                lastModified = bookmark.lastUpdated.fromPlatform(),
                bookmarkId = remoteId
            )
            is RemoteBookmark.Page -> null
        }
    }

    override suspend fun markLocalMutationsInFlight(
        localMutations: List<LocalModelMutation<SyncCollectionBookmark>>
    ): List<LocalMutationAck> =
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(localMutations.mapNotNull { it.ack })

    override suspend fun rollbackLocalMutationsInFlight(acks: List<LocalMutationAck>) {
        collectionBookmarksRepository.rollbackMutatedCollectionBookmarksInFlight(acks)
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
                repoMutation.mapModel { syncNote }
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

private class ReadingSessionsRepositoryDataFetcher(
    val readingSessionsRepository: ReadingSessionsSynchronizationRepository
) : LocalDataFetcher<SyncReadingSession> {

    override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncReadingSession>> {
        return readingSessionsRepository.fetchMutatedReadingSessions().map { repoMutation ->
            repoMutation.mapModel { it.model.toSyncEngine() }
        }
    }

    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
        return readingSessionsRepository.remoteResourcesExist(remoteIDs)
    }

    override suspend fun fetchLocalModel(remoteId: String): SyncReadingSession? {
        return readingSessionsRepository.fetchReadingSessionByRemoteId(remoteId)?.toSyncEngine()
    }
}

internal abstract class CallbackResultNotifier<Model>(
    private val callback: SyncEngineCallback
) : ResultNotifier<Model> {
    override suspend fun didFail(message: String) {
        callback.encounteredError(message)
    }
}

private fun logPersistingSyncChanges(
    resourceLabel: String,
    remoteUpdateCount: Int,
    localUpdateCount: Int
) {
    Logger.i {
        "Persisting $remoteUpdateCount $resourceLabel remote updates, " +
            "and clearing $localUpdateCount local updates."
    }
}

internal class ResultReceiver(
    val bookmarksRepository: BookmarksSynchronizationRepository,
    callback: SyncEngineCallback,
    private val writeBoundaryGuard: SyncWriteBoundaryGuard = NoOpSyncWriteBoundaryGuard
) : CallbackResultNotifier<SyncBookmark>(callback) {

    override suspend fun didSucceed(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<SyncBookmark>>,
        processedLocalMutations: List<LocalModelMutation<SyncBookmark>>
    ) {
        val mappedRemotes = newRemoteMutations.map { remoteMutation ->
            remoteMutation.mapModel { it.model.toRemoteInput() }
        }
        val mappedLocals = processedLocalMutations.map { localMutation ->
            localMutation.mapModel { it.model.toRemoteInput() }
        }

        logPersistingSyncChanges("bookmark", mappedRemotes.size, mappedLocals.size)

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = mappedRemotes,
            localMutationsToClear = mappedLocals,
            writeBoundaryGuard = writeBoundaryGuard.toPersistenceWriteBoundaryGuard()
        )
    }
}

private class CollectionsResultReceiver(
    val repository: CollectionsSynchronizationRepository,
    callback: SyncEngineCallback,
    private val writeBoundaryGuard: SyncWriteBoundaryGuard = NoOpSyncWriteBoundaryGuard
) : CallbackResultNotifier<SyncCollection>(callback) {

    override suspend fun didSucceed(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<SyncCollection>>,
        processedLocalMutations: List<LocalModelMutation<SyncCollection>>
    ) {
        val mappedRemotes = newRemoteMutations.map { remoteMutation ->
            remoteMutation.mapModel { it.model.toRemoteInput() }
        }
        val mappedLocals = processedLocalMutations.map { localMutation ->
            localMutation.mapModel { it.model.toPersistence() }
        }

        logPersistingSyncChanges("collection", mappedRemotes.size, mappedLocals.size)

        repository.applyRemoteChanges(
            updatesToPersist = mappedRemotes,
            localMutationsToClear = mappedLocals,
            writeBoundaryGuard = writeBoundaryGuard.toPersistenceWriteBoundaryGuard()
        )
    }
}

private class CollectionBookmarksResultReceiver(
    val repository: CollectionBookmarksSynchronizationRepository,
    callback: SyncEngineCallback,
    private val writeBoundaryGuard: SyncWriteBoundaryGuard = NoOpSyncWriteBoundaryGuard
) : CallbackResultNotifier<SyncCollectionBookmark>(callback) {

    override suspend fun didSucceed(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
        processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
    ) {
        val mappedRemotes = newRemoteMutations.map { remoteMutation ->
            remoteMutation.mapModel { it.model.toRemoteInput() }
        }
        val mappedLocals = processedLocalMutations.map { localMutation ->
            localMutation.mapModel { it.model.toPersistence(it.localID) }
        }

        logPersistingSyncChanges("collection bookmark", mappedRemotes.size, mappedLocals.size)

        repository.applyRemoteChanges(
            updatesToPersist = mappedRemotes,
            localMutationsToClear = mappedLocals,
            writeBoundaryGuard = writeBoundaryGuard.toPersistenceWriteBoundaryGuard()
        )
    }
}

private class NotesResultReceiver(
    val repository: NotesSynchronizationRepository,
    callback: SyncEngineCallback,
    private val writeBoundaryGuard: SyncWriteBoundaryGuard = NoOpSyncWriteBoundaryGuard
) : CallbackResultNotifier<SyncNote>(callback) {
    private val logger = Logger.withTag("NotesResultReceiver")

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
                remoteMutation.mapModel { remoteNote }
            }
        }

        val mappedLocals = processedLocalMutations.mapNotNull { localMutation ->
            val persistenceNote = localMutation.model.toPersistence(localMutation.localID)
            if (persistenceNote == null) {
                logger.w { "Skipping local note mutation without valid ranges: localId=${localMutation.localID}" }
                null
            } else {
                localMutation.mapModel { persistenceNote }
            }
        }

        logPersistingSyncChanges("note", mappedRemotes.size, mappedLocals.size)

        repository.applyRemoteChanges(
            updatesToPersist = mappedRemotes,
            localMutationsToClear = mappedLocals,
            writeBoundaryGuard = writeBoundaryGuard.toPersistenceWriteBoundaryGuard()
        )
    }
}

private class ReadingSessionsResultReceiver(
    val repository: ReadingSessionsSynchronizationRepository,
    callback: SyncEngineCallback,
    private val writeBoundaryGuard: SyncWriteBoundaryGuard = NoOpSyncWriteBoundaryGuard
) : CallbackResultNotifier<SyncReadingSession>(callback) {

    override suspend fun didSucceed(
        newToken: Long,
        newRemoteMutations: List<RemoteModelMutation<SyncReadingSession>>,
        processedLocalMutations: List<LocalModelMutation<SyncReadingSession>>
    ) {
        val mappedRemotes = newRemoteMutations.map { remoteMutation ->
            remoteMutation.mapModel { it.model.toRemoteInput() }
        }

        val mappedLocals = processedLocalMutations.map { localMutation ->
            localMutation.mapModel { it.model.toPersistence(it.localID) }
        }

        logPersistingSyncChanges("reading session", mappedRemotes.size, mappedLocals.size)

        repository.applyRemoteChangesForMutations(
            updatesToPersist = mappedRemotes,
            localMutationsToClear = mappedLocals,
            writeBoundaryGuard = writeBoundaryGuard.toPersistenceWriteBoundaryGuard()
        )
    }
}

private fun RemoteBookmark.toSyncEngine(id: String): SyncBookmark {
    return when (this) {
        is RemoteBookmark.Ayah -> SyncBookmark.AyahBookmark(
            id = id,
            sura = this.sura,
            ayah = this.ayah,
            lastModified = this.lastUpdated.fromPlatform(),
            isReading = this.isReading
        )
        is RemoteBookmark.Page -> SyncBookmark.PageBookmark(
            id = id,
            page = this.page,
            lastModified = this.lastUpdated.fromPlatform(),
            isReading = this.isReading
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
        is SyncBookmark.AyahBookmark ->
            RemoteBookmark.Ayah(
                sura = this.sura,
                ayah = this.ayah,
                lastUpdated = this.lastModified.toPlatform(),
                isReading = this.isReading
            )
        is SyncBookmark.PageBookmark ->
            RemoteBookmark.Page(
                page = this.page,
                lastUpdated = this.lastModified.toPlatform(),
                isReading = this.isReading
            )
    }
}

private fun SyncCollection.toRemoteInput(): RemoteCollection {
    return RemoteCollection(
        name = this.name,
        lastUpdated = this.lastModified.toPlatform()
    )
}

private fun CollectionAyahBookmark.toSyncEngine(): SyncCollectionBookmark {
    val collectionId = requireNotNull(collectionRemoteId) { "Collection remote ID is required for sync." }
    return SyncCollectionBookmark.AyahBookmark(
        collectionId = collectionId,
        sura = sura,
        ayah = ayah,
        lastModified = lastUpdated.fromPlatform(),
        bookmarkId = bookmarkRemoteId
    )
}

private fun SyncCollectionBookmark.toPersistence(localId: String): CollectionAyahBookmark {
    val updatedAt = lastModified.toPlatform()
    return when (this) {
        is SyncCollectionBookmark.AyahBookmark ->
            CollectionAyahBookmark(
                collectionLocalId = "",
                collectionRemoteId = collectionId,
                bookmarkLocalId = "",
                bookmarkRemoteId = bookmarkId,
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
    return SyncNote(
        id = localId,
        body = body,
        ranges = listOf(
            NoteRange(
                start = NoteAyah(sura = startSura, ayah = startAyah),
                end = NoteAyah(sura = endSura, ayah = endAyah)
            )
        ),
        lastModified = lastUpdated.fromPlatform()
    )
}

private fun SyncNote.toPersistence(localId: String): Note? {
    val range = primaryRangeOrNull() ?: return null
    suraAyahToAyahId(range.start.sura, range.start.ayah) ?: return null
    suraAyahToAyahId(range.end.sura, range.end.ayah) ?: return null
    val noteBody = requireNotNull(body) { "Transforming a note without a body." }
    return Note(
        body = noteBody,
        startSura = range.start.sura,
        startAyah = range.start.ayah,
        endSura = range.end.sura,
        endAyah = range.end.ayah,
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
        lastUpdated = lastModified.toPlatform(),
        semanticReplayEligible = ranges.size == 1
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

private fun ReadingSession.toSyncEngine(): SyncReadingSession {
    return SyncReadingSession(
        id = localId,
        chapterNumber = sura,
        verseNumber = ayah,
        lastModified = lastUpdated.fromPlatform()
    )
}

private fun SyncReadingSession.toPersistence(localId: String): ReadingSession {
    return ReadingSession(
        sura = chapterNumber,
        ayah = verseNumber,
        lastUpdated = lastModified.toPlatform(),
        localId = localId
    )
}

private fun SyncReadingSession.toRemoteInput(): RemoteReadingSession {
    return RemoteReadingSession(
        chapterNumber = chapterNumber,
        verseNumber = verseNumber,
        lastUpdated = lastModified.toPlatform()
    )
}

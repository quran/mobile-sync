package com.quran.shared.persistence.repository.readingbookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.repository.readingbookmark.extension.toReadingBookmark
import com.quran.shared.persistence.repository.readingbookmark.extension.toReadingBookmarkMutation
import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS
import com.quran.shared.persistence.util.fromPlatform
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
class ReadingBookmarksRepositoryImpl(
    private val database: QuranDatabase
) : ReadingBookmarksRepository, ReadingBookmarksSynchronizationRepository {

    private val logger = Logger.withTag("ReadingBookmarksRepository")
    private val readingBookmarkQueries = lazy { database.reading_bookmarksQueries }

    override suspend fun getReadingBookmark(): ReadingBookmark? {
        return withContext(Dispatchers.IO) {
            readingBookmarkQueries.value.getReadingBookmarks()
                .executeAsList()
                .firstOrNull()
                ?.toReadingBookmark()
        }
    }

    override fun getReadingBookmarkFlow(): Flow<ReadingBookmark?> {
        return readingBookmarkQueries.value.getReadingBookmarks()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.firstOrNull()?.toReadingBookmark() }
    }

    override suspend fun addReadingBookmark(sura: Int, ayah: Int): ReadingBookmark {
        logger.i { "Adding reading bookmark for $sura:$ayah" }
        return withContext(Dispatchers.IO) {
            readingBookmarkQueries.value.addReadingBookmark(
                sura = sura.toLong(),
                ayah = ayah.toLong()
            )
            val record = readingBookmarkQueries.value.getReadingBookmarkForAyah(sura.toLong(), ayah.toLong())
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected reading bookmark for $sura:$ayah after insert." }
            record.toReadingBookmark()
        }
    }

    override suspend fun deleteReadingBookmark(): Boolean {
        logger.i { "Deleting current reading bookmark" }
        return withContext(Dispatchers.IO) {
            val currentReadingBookmarks = readingBookmarkQueries.value.getReadingBookmarks()
                .executeAsList()
                .map { it.toReadingBookmark() }

            if (currentReadingBookmarks.isEmpty()) {
                return@withContext false
            }

            currentReadingBookmarks.forEach { bookmark ->
                readingBookmarkQueries.value.deleteReadingBookmark(
                    sura = bookmark.sura.toLong(),
                    ayah = bookmark.ayah.toLong()
                )
            }
            true
        }
    }

    override suspend fun fetchMutatedReadingBookmarks(): List<LocalModelMutation<ReadingBookmark>> {
        return withContext(Dispatchers.IO) {
            readingBookmarkQueries.value.getUnsyncedReadingBookmarks()
                .executeAsList()
                .map { it.toReadingBookmarkMutation() }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark.Ayah>>,
        localMutationsToClear: List<LocalModelMutation<ReadingBookmark>>
    ) {
        logger.i {
            "Applying remote reading bookmark changes with ${updatesToPersist.size} updates to persist and " +
                "${localMutationsToClear.size} local mutations to clear"
        }
        return withContext(Dispatchers.IO) {
            database.transaction {
                val committedCreationKeys = updatesToPersist
                    .filter { it.mutation == Mutation.CREATED }
                    .map { it.model.key() }
                    .toSet()

                localMutationsToClear.forEach { local ->
                    when (local.mutation) {
                        Mutation.DELETED -> clearLocalMutation(local)
                        Mutation.CREATED, Mutation.MODIFIED -> {
                            val localKey = local.model.key()
                            if (!committedCreationKeys.contains(localKey)) {
                                clearLocalMutation(local)
                            }
                        }
                    }
                }

                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED, Mutation.MODIFIED -> applyRemoteReadingBookmarkAddition(remote)
                        Mutation.DELETED -> applyRemoteReadingBookmarkDeletion(remote)
                    }
                }
            }
        }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        if (remoteIDs.isEmpty()) {
            return emptyMap()
        }

        return withContext(Dispatchers.IO) {
            val existentIDs = mutableSetOf<String>()
            remoteIDs.chunked(SQLITE_MAX_BIND_PARAMETERS).forEach { chunk ->
                existentIDs.addAll(
                    readingBookmarkQueries.value.checkRemoteIDsExistence(chunk)
                        .executeAsList()
                        .mapNotNull { it.remote_id }
                )
            }

            remoteIDs.associateWith { existentIDs.contains(it) }
        }
    }

    override suspend fun fetchReadingBookmarkByRemoteId(remoteId: String): ReadingBookmark? {
        return withContext(Dispatchers.IO) {
            readingBookmarkQueries.value.getReadingBookmarkByRemoteId(remoteId)
                .executeAsOneOrNull()
                ?.toReadingBookmark()
        }
    }

    private fun clearLocalMutation(local: LocalModelMutation<ReadingBookmark>) {
        readingBookmarkQueries.value.clearLocalMutationFor(id = local.localID.toLong())
    }

    private fun applyRemoteReadingBookmarkAddition(remote: RemoteModelMutation<RemoteBookmark.Ayah>) {
        val model = remote.model
        val updatedAt = model.lastUpdated.fromPlatform().toEpochMilliseconds()
        readingBookmarkQueries.value.persistRemoteReadingBookmark(
            remote_id = remote.remoteID,
            sura = model.sura.toLong(),
            ayah = model.ayah.toLong(),
            created_at = updatedAt,
            modified_at = updatedAt
        )
    }

    private fun applyRemoteReadingBookmarkDeletion(remote: RemoteModelMutation<RemoteBookmark.Ayah>) {
        readingBookmarkQueries.value.hardDeleteReadingBookmarkFor(remoteID = remote.remoteID)
    }
}

private fun ReadingBookmark.key(): String = "$sura:$ayah"

private fun RemoteBookmark.Ayah.key(): String = "$sura:$ayah"

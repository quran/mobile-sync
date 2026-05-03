package com.quran.shared.persistence.repository.bookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.repository.bookmark.extension.toAyahBookmark
import com.quran.shared.persistence.repository.bookmark.extension.toBookmarkMutation
import com.quran.shared.persistence.util.QuranData
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
class BookmarksRepositoryImpl(
    private val database: QuranDatabase
) : BookmarksRepository, BookmarksSynchronizationRepository {

    private val logger = Logger.withTag("BookmarksRepository")
    private val ayahBookmarkQueries = lazy { database.ayah_bookmarksQueries }

    override suspend fun getAllBookmarks(): List<AyahBookmark> {
        return withContext(Dispatchers.IO) {
            ayahBookmarkQueries.value.getBookmarks()
                .executeAsList()
                .map { it.toAyahBookmark() }
                .sortedByDescending { it.lastUpdated.fromPlatform().toEpochMilliseconds() }
        }
    }

    override fun getBookmarksFlow(): Flow<List<AyahBookmark>> {
        return ayahBookmarkQueries.value.getBookmarks()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { it.toAyahBookmark() }
                    .sortedByDescending { it.lastUpdated.fromPlatform().toEpochMilliseconds() }
            }
    }

    override suspend fun addBookmark(sura: Int, ayah: Int): AyahBookmark {
        logger.i { "Adding ayah bookmark for $sura:$ayah" }
        return withContext(Dispatchers.IO) {
            val ayahId = getAyahId(sura, ayah)
            ayahBookmarkQueries.value.addNewBookmark(
                ayah_id = ayahId.toLong(),
                sura = sura.toLong(),
                ayah = ayah.toLong()
            )
            val record = ayahBookmarkQueries.value.getBookmarkForAyah(sura.toLong(), ayah.toLong())
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected ayah bookmark for $sura:$ayah after insert." }
            record.toAyahBookmark()
        }
    }

    override suspend fun deleteBookmark(sura: Int, ayah: Int): Boolean {
        logger.i { "Deleting ayah bookmark for $sura:$ayah" }
        withContext(Dispatchers.IO) {
            ayahBookmarkQueries.value.deleteBookmark(sura.toLong(), ayah.toLong())
        }
        return true
    }

    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<AyahBookmark>> {
        return withContext(Dispatchers.IO) {
            ayahBookmarkQueries.value.getUnsyncedBookmarks()
                .executeAsList()
                .map { it.toBookmarkMutation() }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark.Ayah>>,
        localMutationsToClear: List<LocalModelMutation<AyahBookmark>>
    ) {
        logger.i {
            "Applying remote changes with ${updatesToPersist.size} updates to persist and " +
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
                        Mutation.CREATED, Mutation.MODIFIED -> applyRemoteBookmarkAddition(remote)
                        Mutation.DELETED -> applyRemoteBookmarkDeletion(remote)
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
                    ayahBookmarkQueries.value.checkRemoteIDsExistence(chunk)
                        .executeAsList()
                        .mapNotNull { it.remote_id }
                )
            }

            remoteIDs.associateWith { existentIDs.contains(it) }
        }
    }

    override suspend fun fetchBookmarkByRemoteId(remoteId: String): AyahBookmark? {
        return withContext(Dispatchers.IO) {
            ayahBookmarkQueries.value.getBookmarkByRemoteId(remoteId)
                .executeAsOneOrNull()
                ?.toAyahBookmark()
        }
    }

    private fun clearLocalMutation(local: LocalModelMutation<AyahBookmark>) {
        ayahBookmarkQueries.value.clearLocalMutationFor(id = local.localID.toLong())
    }

    private fun applyRemoteBookmarkAddition(remote: RemoteModelMutation<RemoteBookmark.Ayah>) {
        val model = remote.model
        val ayahId = getAyahId(model.sura, model.ayah)
        val updatedAt = model.lastUpdated.fromPlatform().toEpochMilliseconds()
        ayahBookmarkQueries.value.persistRemoteBookmark(
            remote_id = remote.remoteID,
            ayah_id = ayahId.toLong(),
            sura = model.sura.toLong(),
            ayah = model.ayah.toLong(),
            created_at = updatedAt,
            modified_at = updatedAt
        )
    }

    private fun applyRemoteBookmarkDeletion(remote: RemoteModelMutation<RemoteBookmark.Ayah>) {
        ayahBookmarkQueries.value.hardDeleteBookmarkFor(remoteID = remote.remoteID)
    }

    private fun getAyahId(sura: Int, ayah: Int): Int {
        return QuranData.getAyahId(sura, ayah)
    }
}

private fun AyahBookmark.key(): String = "$sura:$ayah"

private fun RemoteBookmark.Ayah.key(): String = "$sura:$ayah"

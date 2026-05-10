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
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toEpochMillisecondsOrNull
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
    private val bookmarkCollectionQueries = lazy { database.bookmark_collectionsQueries }

    override suspend fun getAllBookmarks(): List<AyahBookmark> {
        return withContext(Dispatchers.IO) {
            ayahBookmarkQueries.value.getBookmarks()
                .executeAsList()
                .map { it.toAyahBookmark() }
        }
    }

    override fun getBookmarksFlow(): Flow<List<AyahBookmark>> {
        return ayahBookmarkQueries.value.getBookmarks()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { it.toAyahBookmark() }
            }
    }

    override suspend fun addBookmark(sura: Int, ayah: Int): AyahBookmark {
        return addBookmarkWithTimestampMillis(sura, ayah, timestampMillis = null)
    }

    override suspend fun addBookmark(sura: Int, ayah: Int, timestamp: PlatformDateTime): AyahBookmark {
        return addBookmarkWithTimestampMillis(sura, ayah, timestamp.toEpochMillisecondsOrNull())
    }

    private suspend fun addBookmarkWithTimestampMillis(
        sura: Int,
        ayah: Int,
        timestampMillis: Long?
    ): AyahBookmark {
        logger.i { "Adding ayah bookmark for $sura:$ayah" }
        return withContext(Dispatchers.IO) {
            val ayahId = getAyahId(sura, ayah)
            ayahBookmarkQueries.value.addNewBookmark(
                ayah_id = ayahId.toLong(),
                sura = sura.toLong(),
                ayah = ayah.toLong(),
                timestamp = timestampMillis
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
            database.transaction {
                val bookmark = ayahBookmarkQueries.value
                    .getBookmarkForAyah(sura.toLong(), ayah.toLong())
                    .executeAsOneOrNull()
                if (bookmark != null) {
                    deleteBookmarkByLocalIdInTransaction(bookmark.local_id.toString())
                }
            }
        }
        return true
    }

    override suspend fun deleteBookmark(bookmark: AyahBookmark): Boolean {
        logger.i { "Deleting ayah bookmark for ${bookmark.sura}:${bookmark.ayah}" }
        return deleteBookmarkWithLocalId(bookmark.localId)
    }

    override suspend fun deleteBookmark(localId: String): Boolean {
        logger.i { "Deleting ayah bookmark localId=$localId" }
        return deleteBookmarkWithLocalId(localId)
    }

    private suspend fun deleteBookmarkWithLocalId(localId: String): Boolean {
        withContext(Dispatchers.IO) {
            database.transaction {
                deleteBookmarkByLocalIdInTransaction(localId)
            }
        }
        return true
    }

    private fun deleteBookmarkByLocalIdInTransaction(localId: String) {
        bookmarkCollectionQueries.value.deleteBookmarkFromAllCollections(
            bookmark_local_id = localId
        )
        ayahBookmarkQueries.value.deleteBookmarkByLocalId(
            local_id = localId.toLong()
        )
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

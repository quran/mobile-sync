package com.quran.shared.persistence.repository.collectionbookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.AyahHighlight
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.highlightColorForCollectionName
import com.quran.shared.persistence.repository.bookmark.AyahBookmarkStore
import com.quran.shared.persistence.repository.bookmark.BookmarkDependencyReconciler
import com.quran.shared.persistence.repository.collection.CollectionStore
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant

internal class AyahHighlightsRepository(
    private val database: QuranDatabase,
    private val reconciler: BookmarkDependencyReconciler
) {
    private val bookmarkCollectionQueries = database.bookmark_collectionsQueries
    private val bookmarkQueries = database.bookmarksQueries
    private val ayahBookmarkStore = AyahBookmarkStore(database)
    private val collectionStore = CollectionStore(database)

    fun getHighlightsFlow(): Flow<List<AyahHighlight>> =
        bookmarkCollectionQueries.getCollectionBookmarksWithDetails()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { records ->
                records.mapNotNull { record ->
                    val color = highlightColorForCollectionName(record.collection_name)
                        ?: return@mapNotNull null
                    HighlightRecord(
                        highlight = AyahHighlight(
                            sura = record.sura.toInt(),
                            ayah = record.ayah.toInt(),
                            color = color,
                            lastUpdated = Instant.fromEpochMilliseconds(record.modified_at).toPlatform()
                        ),
                        modifiedAt = record.modified_at
                    )
                }
                    .groupBy { it.highlight.sura to it.highlight.ayah }
                    .values
                    .map { recordsForAyah -> recordsForAyah.maxBy { it.modifiedAt }.highlight }
                    .sortedWith(compareBy(AyahHighlight::sura, AyahHighlight::ayah))
            }

    suspend fun setHighlight(
        sura: Int,
        ayah: Int,
        color: AyahHighlightColor,
        timestamp: PlatformDateTime
    ): AyahHighlight {
        val timestampMillis = timestamp.toEpochMillisecondsFromPlatform()
        return withContext(Dispatchers.IO) {
            var result: AyahHighlight? = null
            database.transaction {
                val existingHighlightCollections = collectionStore.activeHighlightCollections()
                val targetCollection = collectionStore.getOrCreateActiveHighlight(
                    color,
                    timestampMillis,
                    existingHighlightCollections
                ).collection

                val bookmark = ayahBookmarkStore.resolve(
                    sura = sura,
                    ayah = ayah,
                    createdAt = timestampMillis,
                    modifiedAt = timestampMillis
                ).bookmark

                if (!hasActiveMembership(bookmark.local_id, targetCollection.local_id)) {
                    bookmarkCollectionQueries.addBookmarkToCollection(
                        bookmark_local_id = bookmark.local_id,
                        collection_local_id = targetCollection.local_id,
                        timestamp = timestampMillis
                    )
                }
                existingHighlightCollections
                    .asSequence()
                    .map { it.second }
                    .filterNot { it.local_id == targetCollection.local_id }
                    .forEach { collection ->
                        bookmarkCollectionQueries.markBookmarkCollectionDeleted(
                            bookmark_local_id = bookmark.local_id,
                            collection_local_id = collection.local_id,
                            timestamp = timestampMillis
                        )
                    }

                reconciler.reconcile(timestampMillis)
                val membership = requireNotNull(
                    bookmarkCollectionQueries
                        .getCollectionBookmarkFor(bookmark.local_id, targetCollection.local_id)
                        .executeAsOneOrNull()
                ) { "Expected highlight membership for $sura:$ayah after update." }
                result = AyahHighlight(
                    sura,
                    ayah,
                    color,
                    Instant.fromEpochMilliseconds(membership.modified_at).toPlatform()
                )
            }
            requireNotNull(result)
        }
    }

    suspend fun removeHighlight(
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): Boolean {
        val timestampMillis = timestamp.toEpochMillisecondsFromPlatform()
        return withContext(Dispatchers.IO) {
            var removed = false
            database.transaction {
                val bookmark = bookmarkQueries
                    .getBookmarkForAyah(sura.toLong(), ayah.toLong())
                    .executeAsOneOrNull() ?: return@transaction

                collectionStore.activeHighlightCollections()
                    .asSequence()
                    .map { it.second }
                    .filter { collection ->
                        bookmarkCollectionQueries
                            .getCollectionBookmarkFor(bookmark.local_id, collection.local_id)
                            .executeAsOneOrNull()
                            ?.is_active == 1L
                    }
                    .forEach { collection ->
                        bookmarkCollectionQueries.markBookmarkCollectionDeleted(
                            bookmark_local_id = bookmark.local_id,
                            collection_local_id = collection.local_id,
                            timestamp = timestampMillis
                        )
                        removed = true
                    }

                if (removed) {
                    reconciler.reconcile(timestampMillis)
                }
            }
            removed
        }
    }

    private fun hasActiveMembership(bookmarkLocalId: Long, collectionLocalId: Long): Boolean =
        bookmarkCollectionQueries
            .getCollectionBookmarkFor(bookmarkLocalId, collectionLocalId)
            .executeAsOneOrNull()
            ?.is_active == 1L

    private data class HighlightRecord(
        val highlight: AyahHighlight,
        val modifiedAt: Long
    )
}

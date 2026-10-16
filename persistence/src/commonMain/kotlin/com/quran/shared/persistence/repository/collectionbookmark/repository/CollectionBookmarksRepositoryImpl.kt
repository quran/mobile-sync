package com.quran.shared.persistence.repository.collectionbookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.RecordNotFoundException
import com.quran.shared.persistence.RecordType
import com.quran.shared.persistence.model.AyahHighlight
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.model.highlightColorForCollectionName
import com.quran.shared.persistence.repository.collection.CollectionStore
import com.quran.shared.persistence.repository.collection.isHighlight
import com.quran.shared.persistence.repository.collectionbookmark.AyahBookmarkDates
import com.quran.shared.persistence.repository.collectionbookmark.parseAyahBookmarkId
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.currentPlatformDateTime
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncv2.db.SyncV2Database
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.native.HiddenFromObjC
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
@HiddenFromObjC
class CollectionBookmarksRepositoryImpl(
    private val database: SyncV2Database
) : CollectionBookmarksRepository {

    private val store = CollectionStore(database)

    override fun getHighlightsFlow(): Flow<List<AyahHighlight>> =
        database.collectionsQueries.collectionAyahsAll().asFlow().map { withContext(Dispatchers.IO) { highlights() } }

    /** One color per verse: the newest, should the verse have more than one before a sync settles them. */
    private fun highlights(): List<AyahHighlight> {
        val colors = store.highlightCollections().entries.associate { (color, row) -> row.local_id to color }
        return store.allVerses().filter { it.collection_local_id in colors }
            .groupBy { it.sura to it.ayah }.values
            .map { memberships ->
                val newest = memberships.maxBy { it.created_at }
                AyahHighlight(
                    sura = newest.sura.toInt(),
                    ayah = newest.ayah.toInt(),
                    color = colors.getValue(newest.collection_local_id),
                    lastUpdated = Instant.fromEpochMilliseconds(newest.created_at).toPlatform()
                )
            }
            .sortedWith(compareBy(AyahHighlight::sura, AyahHighlight::ayah))
    }

    override suspend fun setHighlight(
        sura: Int,
        ayah: Int,
        color: AyahHighlightColor,
        timestamp: PlatformDateTime
    ): AyahHighlight {
        val at = timestamp.toEpochMillisecondsFromPlatform()
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val membership = store.setHighlight(sura, ayah, color, at)
                AyahHighlight(sura, ayah, color, Instant.fromEpochMilliseconds(membership.created_at).toPlatform())
            }
        }
    }

    override suspend fun deleteHighlight(sura: Int, ayah: Int): Boolean =
        deleteHighlight(sura, ayah, currentPlatformDateTime())

    /** A removal has no time of its own to keep: the verse's highlight memberships are deleted. */
    override suspend fun deleteHighlight(sura: Int, ayah: Int, timestamp: PlatformDateTime): Boolean =
        withContext(Dispatchers.IO) {
            database.transactionWithResult { store.clearHighlights(sura, ayah) }
        }

    override suspend fun getBookmarksForCollection(collectionId: String): List<CollectionAyahBookmark> =
        withContext(Dispatchers.IO) { bookmarksIn(collectionId) }

    override fun getBookmarksForCollectionFlow(collectionId: String): Flow<List<CollectionAyahBookmark>> =
        database.collectionsQueries.collectionAyahsAll().asFlow().map { withContext(Dispatchers.IO) { bookmarksIn(collectionId) } }

    /** The collection's verses, most recently added first. */
    private fun bookmarksIn(collectionId: String): List<CollectionAyahBookmark> {
        val localId = collectionId.toLongOrNull() ?: return emptyList()
        val dates = dates()
        return store.versesIn(localId)
            .sortedWith(compareByDescending<com.quran.shared.syncv2.db.Collection_ayah> { it.created_at }.thenBy { it.sura }.thenBy { it.ayah })
            .map(dates::collectionAyahBookmark)
    }

    override suspend fun addAyahBookmarkToCollection(collectionId: String, sura: Int, ayah: Int): CollectionAyahBookmark =
        addAyahBookmarkToCollection(collectionId, sura, ayah, currentPlatformDateTime())

    override suspend fun addAyahBookmarkToCollection(
        collectionId: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark {
        val at = timestamp.toEpochMillisecondsFromPlatform()
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val collection = collectionId.toLongOrNull()?.let(store::get)
                    ?: throw RecordNotFoundException(RecordType.COLLECTION, collectionId)
                require(!collection.isHighlight) { "Highlight collections must be changed through setHighlight." }
                store.addVerse(collection.local_id, sura, ayah, at)
                dates().collectionAyahBookmark(requireNotNull(store.membership(collection.local_id, sura, ayah)))
            }
        }
    }

    override suspend fun removeAyahBookmarkFromCollection(collectionId: String, bookmarkId: String): Boolean =
        withContext(Dispatchers.IO) {
            val collection = collectionId.toLongOrNull() ?: return@withContext false
            val (sura, ayah) = parseAyahBookmarkId(bookmarkId) ?: return@withContext false
            database.transactionWithResult { store.removeVerse(collection, sura, ayah) }
        }

    private fun dates() = AyahBookmarkDates(
        store.allVerses(),
        store.all().filter { highlightColorForCollectionName(it.name) != null }.map { it.local_id }.toSet()
    )
}

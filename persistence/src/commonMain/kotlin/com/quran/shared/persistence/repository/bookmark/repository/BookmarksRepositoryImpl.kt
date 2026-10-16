package com.quran.shared.persistence.repository.bookmark.repository

import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.RecordNotFoundException
import com.quran.shared.persistence.RecordType
import com.quran.shared.persistence.model.BookmarkCollectionsReplacementResult
import com.quran.shared.persistence.repository.collection.CollectionStore
import com.quran.shared.persistence.repository.collection.isHighlight
import com.quran.shared.persistence.repository.collectionbookmark.AyahBookmarkDates
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.currentPlatformDateTime
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
import com.quran.shared.syncv2.db.SyncV2Database
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.native.HiddenFromObjC

@Inject
@SingleIn(AppScope::class)
@HiddenFromObjC
class BookmarksRepositoryImpl(
    private val database: SyncV2Database
) : BookmarksRepository {

    private val logger = Logger.withTag("BookmarksRepository")
    private val store = CollectionStore(database)

    override suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>
    ): BookmarkCollectionsReplacementResult = replaceAyahBookmarkCollections(sura, ayah, collectionIds, currentPlatformDateTime())

    override suspend fun replaceAyahBookmarkCollections(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>,
        timestamp: PlatformDateTime
    ): BookmarkCollectionsReplacementResult {
        val at = timestamp.toEpochMillisecondsFromPlatform()
        val requested = collectionIds.map(String::trim).filter(String::isNotEmpty).toSet()
        logger.i { "Replacing ayah bookmark collection memberships for $sura:$ayah" }
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val desired = requested.mapNotNull { id ->
                    val collection = id.toLongOrNull()?.let(store::get)
                        ?: throw RecordNotFoundException(RecordType.COLLECTION, id)
                    // Highlights are kept as they are, whatever the request says.
                    collection.local_id.takeUnless { collection.isHighlight }
                }.toSet()
                val highlights = store.highlightCollections().values.map { it.local_id }.toSet()
                val current = store.membershipsOf(sura, ayah).map { it.collection_local_id }.filter { it !in highlights }.toSet()
                // A verse moved between saved collections keeps its bookmark date: new memberships take the earliest
                // current one's, so only the first save dates the bookmark.
                val savedAt = store.membershipsOf(sura, ayah).filter { it.collection_local_id in current }.minOfOrNull { it.created_at } ?: at
                (desired - current).forEach { store.addVerse(it, sura, ayah, savedAt) }
                (current - desired).forEach { store.removeVerse(it, sura, ayah) }
                val bookmark = if (desired.isEmpty()) null else AyahBookmarkDates(store.allVerses(), highlights).ayahBookmark(sura, ayah)
                BookmarkCollectionsReplacementResult(bookmark, changed = desired != current)
            }
        }
    }
}

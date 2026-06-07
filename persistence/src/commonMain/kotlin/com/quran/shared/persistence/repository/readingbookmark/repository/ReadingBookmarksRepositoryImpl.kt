package com.quran.shared.persistence.repository.readingbookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.repository.bookmark.BookmarkDependencyReconciler
import com.quran.shared.persistence.repository.readingbookmark.extension.toAyahReadingBookmark
import com.quran.shared.persistence.repository.readingbookmark.extension.toPageReadingBookmark
import com.quran.shared.persistence.repository.readingbookmark.extension.toReadingBookmark
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.QuranData
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
class ReadingBookmarksRepositoryImpl(
    private val database: QuranDatabase,
    private val reconciler: BookmarkDependencyReconciler = BookmarkDependencyReconciler(database)
) : ReadingBookmarksRepository {

    private val logger = Logger.withTag("ReadingBookmarksRepository")
    private val bookmarkQueries = lazy { database.bookmarksQueries }
    private val bookmarkCollectionQueries = lazy { database.bookmark_collectionsQueries }

    override suspend fun getReadingBookmark(): ReadingBookmark? {
        return withContext(Dispatchers.IO) {
            bookmarkQueries.value.getCurrentReadingBookmark()
                .executeAsOneOrNull()
                ?.toReadingBookmark()
        }
    }

    override fun getReadingBookmarkFlow(): Flow<ReadingBookmark?> {
        return bookmarkQueries.value.getCurrentReadingBookmark()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.firstOrNull()?.toReadingBookmark() }
    }

    override suspend fun addAyahReadingBookmark(sura: Int, ayah: Int): AyahReadingBookmark {
        return addAyahReadingBookmarkWithTimestampMillis(sura, ayah, timestampMillis = null)
    }

    override suspend fun addAyahReadingBookmark(
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): AyahReadingBookmark {
        return addAyahReadingBookmarkWithTimestampMillis(sura, ayah, timestamp.toEpochMillisecondsOrNull())
    }

    private suspend fun addAyahReadingBookmarkWithTimestampMillis(
        sura: Int,
        ayah: Int,
        timestampMillis: Long?
    ): AyahReadingBookmark {
        logger.i { "Adding ayah reading bookmark for $sura:$ayah" }
        return withContext(Dispatchers.IO) {
            var created: AyahReadingBookmark? = null
            database.transaction {
                bookmarkQueries.value.setAyahReadingBookmark(
                    ayah_id = getAyahId(sura, ayah).toLong(),
                    sura = sura.toLong(),
                    ayah = ayah.toLong(),
                    timestamp = timestampMillis
                )
                val row = requireNotNull(
                    bookmarkQueries.value.getBookmarkForAyah(sura.toLong(), ayah.toLong()).executeAsOneOrNull()
                ) { "Expected reading bookmark for $sura:$ayah after insert." }
                bookmarkQueries.value.clearOtherReadingBookmarks(
                    local_id = row.local_id,
                    timestamp = timestampMillis
                )
                reconciler.reconcile()
                created = bookmarkQueries.value
                    .getBookmarkForAyah(sura.toLong(), ayah.toLong())
                    .executeAsOne()
                    .toAyahReadingBookmark()
            }
            requireNotNull(created)
        }
    }

    override suspend fun addPageReadingBookmark(page: Int): PageReadingBookmark {
        return addPageReadingBookmarkWithTimestampMillis(page, timestampMillis = null)
    }

    override suspend fun addPageReadingBookmark(page: Int, timestamp: PlatformDateTime): PageReadingBookmark {
        return addPageReadingBookmarkWithTimestampMillis(page, timestamp.toEpochMillisecondsOrNull())
    }

    private suspend fun addPageReadingBookmarkWithTimestampMillis(
        page: Int,
        timestampMillis: Long?
    ): PageReadingBookmark {
        logger.i { "Adding page reading bookmark for page=$page" }
        return withContext(Dispatchers.IO) {
            var created: PageReadingBookmark? = null
            database.transaction {
                bookmarkQueries.value.setPageReadingBookmark(
                    page = page.toLong(),
                    timestamp = timestampMillis
                )
                val row = requireNotNull(
                    bookmarkQueries.value.getBookmarkForPage(page.toLong()).executeAsOneOrNull()
                ) { "Expected reading bookmark for page=$page after insert." }
                bookmarkQueries.value.clearOtherReadingBookmarks(
                    local_id = row.local_id,
                    timestamp = timestampMillis
                )
                reconciler.reconcile()
                created = bookmarkQueries.value
                    .getBookmarkForPage(page.toLong())
                    .executeAsOne()
                    .toPageReadingBookmark()
            }
            requireNotNull(created)
        }
    }

    override suspend fun deleteReadingBookmark(): Boolean {
        logger.i { "Deleting current reading bookmark" }
        return withContext(Dispatchers.IO) {
            var deleted = false
            database.transaction {
                val row = bookmarkQueries.value.getCurrentReadingBookmark().executeAsOneOrNull()
                    ?: return@transaction
                val hasSavedMembership =
                    row.is_in_default_collection == 1L ||
                        bookmarkCollectionQueries.value.countActiveForBookmark(row.local_id).executeAsOne() > 0
                when {
                    hasSavedMembership -> bookmarkQueries.value.clearReadingBookmark(
                        local_id = row.local_id,
                        timestamp = null
                    )
                    row.remote_id == null -> bookmarkQueries.value.hardDeleteBookmarkByLocalId(row.local_id)
                    else -> bookmarkQueries.value.markBookmarkDeleted(
                        local_id = row.local_id,
                        timestamp = null
                    )
                }
                reconciler.reconcile()
                deleted = true
            }
            deleted
        }
    }

    private fun getAyahId(sura: Int, ayah: Int): Int {
        return QuranData.getAyahId(sura, ayah)
    }
}

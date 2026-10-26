package com.quran.shared.persistence.repository.readingbookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.EmptyReadingBookmark
import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.model.toReadingBookmarkSlot
import com.quran.shared.persistence.repository.readingbookmark.ReadingBookmarkStore
import com.quran.shared.persistence.repository.readingbookmark.location
import com.quran.shared.persistence.repository.readingbookmark.mapping.ReadingBookmarkPageMapper
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.currentPlatformDateTime
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncv2.db.Reading_bookmark
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.ReadingLocation
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
class ReadingBookmarksRepositoryImpl(
    private val database: SyncV2Database
) : ReadingBookmarksRepository {

    private val store = ReadingBookmarkStore(database)

    override suspend fun getReadingBookmarks(mushaf: Mushaf): List<ReadingBookmark> =
        withContext(Dispatchers.IO) { store.all().mapNotNull { it.toReadingBookmark(mushaf) } }

    override fun getReadingBookmarksFlow(mushaf: Mushaf): Flow<List<ReadingBookmark>> =
        database.readingBookmarksQueries.all().asFlow().map {
            withContext(Dispatchers.IO) { store.all().mapNotNull { row -> row.toReadingBookmark(mushaf) } }
        }

    override suspend fun setAyahReadingBookmark(slot: ReadingBookmarkSlot, sura: Int, ayah: Int, mushaf: Mushaf): ReadingBookmark =
        setAyahReadingBookmark(slot, sura, ayah, mushaf, currentPlatformDateTime())

    override suspend fun setAyahReadingBookmark(
        slot: ReadingBookmarkSlot,
        sura: Int,
        ayah: Int,
        mushaf: Mushaf,
        timestamp: PlatformDateTime
    ): ReadingBookmark = write(mushaf) {
        store.setLocation(slot, ReadingLocation.ayah(sura, ayah), timestamp.toEpochMillisecondsFromPlatform())
    }

    override suspend fun setPageReadingBookmark(slot: ReadingBookmarkSlot, page: Int, mushaf: Mushaf): ReadingBookmark =
        setPageReadingBookmark(slot, page, mushaf, currentPlatformDateTime())

    override suspend fun setPageReadingBookmark(
        slot: ReadingBookmarkSlot,
        page: Int,
        mushaf: Mushaf,
        timestamp: PlatformDateTime
    ): ReadingBookmark {
        val location = ReadingBookmarkPageMapper.serverLocation(page, mushaf)
        return write(mushaf) { store.setLocation(slot, location, timestamp.toEpochMillisecondsFromPlatform()) }
    }

    override suspend fun renameReadingBookmark(slot: ReadingBookmarkSlot, name: String?, mushaf: Mushaf): ReadingBookmark =
        renameReadingBookmark(slot, name, mushaf, currentPlatformDateTime())

    override suspend fun renameReadingBookmark(
        slot: ReadingBookmarkSlot,
        name: String?,
        mushaf: Mushaf,
        timestamp: PlatformDateTime
    ): ReadingBookmark = write(mushaf) { store.rename(slot, name, timestamp.toEpochMillisecondsFromPlatform()) }

    override suspend fun clearReadingBookmark(slot: ReadingBookmarkSlot, mushaf: Mushaf): ReadingBookmark =
        clearReadingBookmark(slot, mushaf, currentPlatformDateTime())

    override suspend fun clearReadingBookmark(slot: ReadingBookmarkSlot, mushaf: Mushaf, timestamp: PlatformDateTime): ReadingBookmark =
        write(mushaf) { store.setLocation(slot, null, timestamp.toEpochMillisecondsFromPlatform()) }

    private suspend fun write(mushaf: Mushaf, block: () -> Reading_bookmark): ReadingBookmark = withContext(Dispatchers.IO) {
        database.transactionWithResult { block() }.toReadingBookmark(mushaf) ?: error("Expected a reading bookmark slot 1 to 3.")
    }
}

/**
 * A slot as the app shows it, page bookmarks as pages of [mushaf]. A location the app has no model for (a surah or juz
 * set on the web, or a page of a group this library doesn't know) shows as an empty slot; the row keeps it. Slots
 * outside 1 to 3 are not shown.
 */
internal fun Reading_bookmark.toReadingBookmark(mushaf: Mushaf): ReadingBookmark? {
    if (slot !in 1L..3L) return null
    val lastUpdated = Instant.fromEpochMilliseconds(updated_at).toPlatform()
    val slot = slot.toInt().toReadingBookmarkSlot()
    val id = local_id.toString()
    val location = location()
    val page = location?.takeIf { it.type == "page" }?.let { ReadingBookmarkPageMapper.appPage(it, mushaf) }
    return when {
        location?.type == "ayah" -> AyahReadingBookmark(location.key, location.verse!!, lastUpdated, id, slot, name)
        page != null -> PageReadingBookmark(page, mushaf, lastUpdated, id, slot, name)
        else -> EmptyReadingBookmark(slot, name, lastUpdated, id)
    }
}

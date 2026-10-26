package com.quran.shared.persistence.repository.readingbookmark.repository

import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.util.PlatformDateTime
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

/**
 * The three reading bookmark slots. Every call that returns slots takes the [Mushaf] the app shows: page bookmarks
 * come back as pages of that mushaf, mapped from whichever mushaf they were set in, and a page of a layout this
 * library doesn't know comes back as an empty slot (its location is kept). Ayah bookmarks need no mapping.
 */
interface ReadingBookmarksRepository {
    @NativeCoroutines
    suspend fun getReadingBookmarks(mushaf: Mushaf): List<ReadingBookmark>

    @NativeCoroutines
    fun getReadingBookmarksFlow(mushaf: Mushaf): Flow<List<ReadingBookmark>>

    @NativeCoroutines
    suspend fun setAyahReadingBookmark(
        slot: ReadingBookmarkSlot,
        sura: Int,
        ayah: Int,
        mushaf: Mushaf
    ): ReadingBookmark

    @NativeCoroutines
    suspend fun setAyahReadingBookmark(
        slot: ReadingBookmarkSlot,
        sura: Int,
        ayah: Int,
        mushaf: Mushaf,
        timestamp: PlatformDateTime
    ): ReadingBookmark

    /**
     * Sets [slot] to [page] of [mushaf], numbered the way apps number that mushaf's pages (see [Mushaf]).
     *
     * @throws IllegalArgumentException when [mushaf] has no such page.
     */
    @NativeCoroutines
    suspend fun setPageReadingBookmark(slot: ReadingBookmarkSlot, page: Int, mushaf: Mushaf): ReadingBookmark

    @NativeCoroutines
    suspend fun setPageReadingBookmark(
        slot: ReadingBookmarkSlot,
        page: Int,
        mushaf: Mushaf,
        timestamp: PlatformDateTime
    ): ReadingBookmark

    @NativeCoroutines
    suspend fun renameReadingBookmark(slot: ReadingBookmarkSlot, name: String?, mushaf: Mushaf): ReadingBookmark

    @NativeCoroutines
    suspend fun renameReadingBookmark(
        slot: ReadingBookmarkSlot,
        name: String?,
        mushaf: Mushaf,
        timestamp: PlatformDateTime
    ): ReadingBookmark

    @NativeCoroutines
    suspend fun clearReadingBookmark(slot: ReadingBookmarkSlot, mushaf: Mushaf): ReadingBookmark

    @NativeCoroutines
    suspend fun clearReadingBookmark(slot: ReadingBookmarkSlot, mushaf: Mushaf, timestamp: PlatformDateTime): ReadingBookmark
}

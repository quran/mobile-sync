package com.quran.shared.persistence.repository.readingbookmark.repository

import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.util.PlatformDateTime
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

/**
 * The three reading bookmark slots. Every call that can return a page bookmark takes the [Mushaf] the app shows and
 * returns pages of it, mapped from whichever mushaf they were set in.
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
        ayah: Int
    ): ReadingBookmark

    @NativeCoroutines
    suspend fun setAyahReadingBookmark(
        slot: ReadingBookmarkSlot,
        sura: Int,
        ayah: Int,
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
    suspend fun clearReadingBookmark(slot: ReadingBookmarkSlot): ReadingBookmark

    @NativeCoroutines
    suspend fun clearReadingBookmark(slot: ReadingBookmarkSlot, timestamp: PlatformDateTime): ReadingBookmark
}

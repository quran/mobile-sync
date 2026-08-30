package com.quran.shared.persistence.repository.readingbookmark.repository

import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.util.PlatformDateTime
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

interface ReadingBookmarksRepository {
    @NativeCoroutines
    suspend fun getReadingBookmarks(): List<ReadingBookmark>

    @NativeCoroutines
    fun getReadingBookmarksFlow(): Flow<List<ReadingBookmark>>

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

    @NativeCoroutines
    suspend fun setPageReadingBookmark(slot: ReadingBookmarkSlot, page: Int): ReadingBookmark

    @NativeCoroutines
    suspend fun setPageReadingBookmark(
        slot: ReadingBookmarkSlot,
        page: Int,
        timestamp: PlatformDateTime
    ): ReadingBookmark

    @NativeCoroutines
    suspend fun renameReadingBookmark(slot: ReadingBookmarkSlot, name: String?): ReadingBookmark

    @NativeCoroutines
    suspend fun renameReadingBookmark(
        slot: ReadingBookmarkSlot,
        name: String?,
        timestamp: PlatformDateTime
    ): ReadingBookmark

    @NativeCoroutines
    suspend fun clearReadingBookmark(slot: ReadingBookmarkSlot): ReadingBookmark

    @NativeCoroutines
    suspend fun clearReadingBookmark(slot: ReadingBookmarkSlot, timestamp: PlatformDateTime): ReadingBookmark
}

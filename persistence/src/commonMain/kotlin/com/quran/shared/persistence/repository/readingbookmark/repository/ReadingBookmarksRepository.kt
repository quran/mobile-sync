package com.quran.shared.persistence.repository.readingbookmark.repository

import com.quran.shared.persistence.model.ReadingBookmark
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
        slot: Int,
        sura: Int,
        ayah: Int
    ): ReadingBookmark

    @NativeCoroutines
    suspend fun setAyahReadingBookmark(
        slot: Int,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): ReadingBookmark

    @NativeCoroutines
    suspend fun setPageReadingBookmark(slot: Int, page: Int): ReadingBookmark

    @NativeCoroutines
    suspend fun setPageReadingBookmark(slot: Int, page: Int, timestamp: PlatformDateTime): ReadingBookmark

    @NativeCoroutines
    suspend fun renameReadingBookmark(slot: Int, name: String?): ReadingBookmark

    @NativeCoroutines
    suspend fun renameReadingBookmark(slot: Int, name: String?, timestamp: PlatformDateTime): ReadingBookmark

    @NativeCoroutines
    suspend fun clearReadingBookmark(slot: Int): ReadingBookmark

    @NativeCoroutines
    suspend fun clearReadingBookmark(slot: Int, timestamp: PlatformDateTime): ReadingBookmark
}

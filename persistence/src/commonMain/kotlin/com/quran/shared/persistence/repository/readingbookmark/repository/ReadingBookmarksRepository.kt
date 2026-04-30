package com.quran.shared.persistence.repository.readingbookmark.repository

import com.quran.shared.persistence.model.ReadingBookmark
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

interface ReadingBookmarksRepository {
    @NativeCoroutines
    suspend fun getReadingBookmark(): ReadingBookmark?

    @NativeCoroutines
    fun getReadingBookmarkFlow(): Flow<ReadingBookmark?>

    @NativeCoroutines
    suspend fun addReadingBookmark(sura: Int, ayah: Int): ReadingBookmark

    @NativeCoroutines
    suspend fun deleteReadingBookmark(): Boolean
}

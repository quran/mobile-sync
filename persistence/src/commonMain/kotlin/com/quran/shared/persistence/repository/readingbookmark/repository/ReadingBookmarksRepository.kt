package com.quran.shared.persistence.repository.readingbookmark.repository

import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

interface ReadingBookmarksRepository {
    @NativeCoroutines
    suspend fun getReadingBookmark(): ReadingBookmark?

    @NativeCoroutines
    fun getReadingBookmarkFlow(): Flow<ReadingBookmark?>

    @NativeCoroutines
    suspend fun addAyahReadingBookmark(sura: Int, ayah: Int): AyahReadingBookmark

    @NativeCoroutines
    suspend fun addPageReadingBookmark(page: Int): PageReadingBookmark

    @NativeCoroutines
    suspend fun deleteReadingBookmark(): Boolean
}

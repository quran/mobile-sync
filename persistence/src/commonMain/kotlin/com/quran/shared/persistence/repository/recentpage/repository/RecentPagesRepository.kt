package com.quran.shared.persistence.repository.recentpage.repository

import com.quran.shared.persistence.model.RecentPage
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import kotlinx.coroutines.flow.Flow

interface RecentPagesRepository {
    /**
     * Fetch and returns all recent pages.
     *
     * @return List<RecentPage> the current list of recent pages
     */
    @NativeCoroutines
    suspend fun getRecentPages(): List<RecentPage>

    /**
     * Add a page to the recent pages list.
     *
     * @param page the page number
     * @param firstAyahSura the sura number of the first ayah on the page
     * @param firstAyahVerse the verse number of the first ayah on the page
     * @return the [RecentPage]
     */
    @NativeCoroutines
    suspend fun addRecentPage(page: Int, firstAyahSura: Int, firstAyahVerse: Int): RecentPage

    /**
     * Returns a flow of all recent pages for observation.
     */
    @NativeCoroutines
    fun getRecentPagesFlow(): Flow<List<RecentPage>>

    /**
     * Delete a page from the recent pages list.
     *
     * @return a boolean denoting success
     */
    @NativeCoroutines
    suspend fun deleteRecentPage(page: Int): Boolean
}

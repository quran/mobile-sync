package com.quran.shared.persistence.repository.recentpage.repository

import com.quran.shared.persistence.model.RecentPage
import com.rickclephas.kmp.nativecoroutines.NativeCoroutines

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
     * @return the [RecentPage]
     */
    @NativeCoroutines
    suspend fun addRecentPage(page: Int): RecentPage

    /**
     * Delete a page from the recent pages list.
     *
     * @return a boolean denoting success
     */
    @NativeCoroutines
    suspend fun deleteRecentPage(page: Int): Boolean
}

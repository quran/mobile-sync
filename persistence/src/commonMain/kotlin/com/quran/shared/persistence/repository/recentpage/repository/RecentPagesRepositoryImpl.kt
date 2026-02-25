package com.quran.shared.persistence.repository.recentpage.repository

import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.model.RecentPage
import com.quran.shared.persistence.repository.recentpage.extension.toRecentPage
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
class RecentPagesRepositoryImpl(
    private val database: QuranDatabase
) : RecentPagesRepository {

    private val logger = Logger.withTag("RecentPagesRepository")
    private val recentPagesQueries = lazy { database.recent_pagesQueries }

    override suspend fun getRecentPages(): List<RecentPage> {
        return withContext(Dispatchers.IO) {
            recentPagesQueries.value.getRecentPages()
                .executeAsList()
                .map { it.toRecentPage() }
        }
    }

    override suspend fun addRecentPage(page: Int): RecentPage {
        logger.i { "Adding recent page $page" }
        return withContext(Dispatchers.IO) {
            recentPagesQueries.value.addRecentPage(page.toLong())
            val record = recentPagesQueries.value.getRecentPageForPage(page.toLong())
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected recent page for page $page after insert." }
            record.toRecentPage()
        }
    }

    override suspend fun deleteRecentPage(page: Int): Boolean {
        logger.i { "Deleting recent page for page $page" }
        withContext(Dispatchers.IO) {
            recentPagesQueries.value.deleteRecentPage(page.toLong())
        }
        return true
    }
}

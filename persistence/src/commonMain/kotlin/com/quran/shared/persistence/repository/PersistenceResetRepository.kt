package com.quran.shared.persistence.repository

import com.quran.shared.persistence.QuranDatabase
import dev.zacsweers.metro.Inject

interface PersistenceResetRepository {
    fun deleteAllData()
}

class PersistenceResetRepositoryImpl @Inject constructor(
    private val database: QuranDatabase
) : PersistenceResetRepository {
    override fun deleteAllData() {
        database.transaction {
            database.ayah_bookmarksQueries.deleteAll()
            database.reading_bookmarksQueries.deleteAll()
            database.bookmark_collectionsQueries.deleteAll()
            database.collectionsQueries.deleteAll()
            database.notesQueries.deleteAll()
            database.reading_sessionsQueries.deleteAll()
        }
    }
}

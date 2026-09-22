package com.quran.shared.persistence.repository.importdata

import com.quran.shared.di.AppScope
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.PersistenceImportResult
import com.quran.shared.persistence.repository.bookmark.BookmarkDependencyReconciler
import com.quran.shared.persistence.util.currentEpochMilliseconds
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
class PersistenceImportRepositoryImpl(
    private val database: QuranDatabase,
    private val reconciler: BookmarkDependencyReconciler = BookmarkDependencyReconciler(database)
) : PersistenceImportRepository {

    override suspend fun importData(
        data: PersistenceImportData,
        deleteExisting: Boolean,
        trackHistory: Boolean
    ): PersistenceImportResult {
        require(!(deleteExisting && trackHistory)) {
            "deleteExisting and trackHistory cannot both be true."
        }
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            context.ensureActive()
            var result: PersistenceImportResult? = null
            database.transaction {
                if (deleteExisting) {
                    deleteExistingData()
                }
                val merged = PersistenceImportMerger(
                    database = database,
                    reconciler = reconciler,
                    data = data,
                    trackHistory = trackHistory,
                    context = context
                ).merge()
                result = merged.copy(changed = deleteExisting || merged.changed)
            }
            requireNotNull(result)
        }
    }

    private fun deleteExistingData() {
        val timestamp = currentEpochMilliseconds()
        database.bookmark_collectionsQueries.markUnsyncedBookmarkCollectionsDeletedForImport(timestamp)
        database.bookmark_collectionsQueries.markRemoteBookmarkCollectionsDeleted(timestamp)
        database.bookmarksQueries.markUnsyncedBookmarksDeletedForImport(timestamp)
        database.bookmarksQueries.markRemoteBookmarksDeleted(timestamp)
        database.collectionsQueries.markUnsyncedCollectionsDeletedForImport(timestamp)
        database.collectionsQueries.markRemoteCollectionsDeleted(timestamp)
        database.notesQueries.markUnsyncedNotesDeletedForImport(timestamp)
        database.notesQueries.markRemoteNotesDeleted(timestamp)
        database.reading_bookmarksQueries.markAllForImportReplacement(timestamp)
        database.reading_sessionsQueries.markUnsyncedReadingSessionsDeletedForImport(timestamp)
        database.reading_sessionsQueries.markRemoteReadingSessionsDeleted(timestamp)
    }
}

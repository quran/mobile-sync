package com.quran.shared.persistence.repository.importdata

import com.quran.shared.di.AppScope
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.PersistenceImportResult
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.repository.collection.CollectionStore
import com.quran.shared.persistence.repository.collection.repository.isReadOnly
import com.quran.shared.persistence.repository.note.NoteStore
import com.quran.shared.persistence.repository.readingbookmark.ReadingBookmarkStore
import com.quran.shared.persistence.repository.readingsession.ReadingSessionStore
import com.quran.shared.persistence.util.currentEpochMilliseconds
import com.quran.shared.syncv2.db.SyncV2Database
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.native.HiddenFromObjC

/**
 * Imports through the repositories' write paths ([NoteStore], [CollectionStore], [ReadingSessionStore],
 * [ReadingBookmarkStore]), so imported rows follow the same rules as the app's own writes: trimmed text, the note
 * creation-time nudge, one highlight color per verse, and the newest 20 reading sessions. An import writes domain rows
 * only; the sync engine pushes them like any other local change.
 */
@Inject
@SingleIn(AppScope::class)
@HiddenFromObjC
class PersistenceImportRepositoryImpl(
    private val database: QuranDatabase,
    private val synced: SyncV2Database
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
            database.transactionWithResult {
                if (deleteExisting) deleteExistingData()
                val merged = PersistenceImportMerger(database, synced, data, trackHistory, context).merge()
                merged.copy(changed = deleteExisting || merged.changed)
            }
        }
    }

    /**
     * Deletes the user's data as local deletes, so the next sync deletes what the server has too. Reading bookmark slots
     * keep their identities and names; their locations are cleared.
     */
    private fun deleteExistingData() {
        val now = currentEpochMilliseconds()
        val notes = NoteStore(synced)
        synced.notesQueries.all().executeAsList().forEach { notes.delete(it.local_id) }
        val sessions = ReadingSessionStore(synced)
        sessions.all().forEach { sessions.delete(it.local_id) }
        val collections = CollectionStore(synced)
        // Favorites, highlights and protected collections can't be deleted: they are emptied instead.
        collections.all().forEach { row ->
            if (row.isReadOnly) {
                collections.versesIn(row.local_id).forEach { collections.removeVerse(row.local_id, it.sura.toInt(), it.ayah.toInt()) }
            } else {
                collections.delete(row.local_id)
            }
        }
        val readingBookmarks = ReadingBookmarkStore(synced)
        ReadingBookmarkSlot.entries.filter { readingBookmarks.get(it)?.location_type != null }
            .forEach { readingBookmarks.setLocation(it, null, now) }
    }
}

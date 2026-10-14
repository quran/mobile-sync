package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersistenceResetRepositoryTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var reset: PersistenceResetRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        reset = PersistenceResetRepositoryImpl(database.quran, database.synced)
    }

    @Test
    fun `reset deletes the user's data`() = runTest {
        val collections = CollectionsRepositoryImpl(database.synced)
        val collectionBookmarks = CollectionBookmarksRepositoryImpl(database.synced)
        val study = collections.addCollection("Study", at(100))
        collectionBookmarks.addAyahBookmarkToCollection(study.id, 2, 255, at(100))
        collectionBookmarks.setHighlight(3, 1, AyahHighlightColor.BLUE, at(100))
        NotesRepositoryImpl(database.synced).addNote("Reflect on it", 2, 255, 2, 255, at(100))
        ReadingSessionsRepositoryImpl(database.synced).addReadingSession(2, 255, at(100))
        ReadingBookmarksRepositoryImpl(database.synced).setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 42, at(100))

        reset.deleteAllData()

        val queries = database.synced
        assertEquals(emptyList(), queries.notesQueries.all().executeAsList())
        assertEquals(emptyList(), queries.notesQueries.allRanges().executeAsList())
        assertEquals(emptyList(), queries.collectionsQueries.collectionAyahsAll().executeAsList())
        assertEquals(emptyList(), queries.sessionsQueries.all().executeAsList())
        assertEquals(emptyList(), queries.readingBookmarksQueries.all().executeAsList())
    }

    @Test
    fun `reset restores Favorites as the only collection`() = runTest {
        CollectionsRepositoryImpl(database.synced).addCollection("Study", at(100))
        CollectionBookmarksRepositoryImpl(database.synced).setHighlight(3, 1, AyahHighlightColor.BLUE, at(100))

        reset.deleteAllData()

        val collections = database.synced.collectionsQueries.collectionsAll().executeAsList()
        assertEquals(listOf("Favorites"), collections.map { it.name })
        assertEquals(1L, collections.single().is_default)
    }

    @Test
    fun `reset wipes the sync state`() {
        database.synced.sync_itemQueries.upsert("NOTE", 1, "remote-note", "{}", null, null, null)
        database.synced.sync_itemQueries.metaSet("token", "sync-token")
        database.synced.sync_itemQueries.metaSet("account", "account-id")

        reset.deleteAllData()

        assertEquals(emptyList(), database.synced.sync_itemQueries.all("NOTE").executeAsList())
        assertNull(database.synced.sync_itemQueries.metaGet("token").executeAsOneOrNull())
        assertNull(database.synced.sync_itemQueries.metaGet("account").executeAsOneOrNull())
    }

    @Test
    fun `reset clears the import history`() {
        database.quran.import_trackingQueries.recordImportHistory("v1:handled")

        reset.deleteAllData()

        assertEquals(emptyList(), database.quran.import_trackingQueries.getRecordedImportHistory(listOf("v1:handled")).executeAsList())
    }

    @Test
    fun `reset keeps the key-value store`() {
        database.quran.key_value_storeQueries.putValue("com.quran.sync.session", "kept")

        reset.deleteAllData()

        assertEquals("kept", database.quran.key_value_storeQueries.getValue("com.quran.sync.session").executeAsOne())
    }
}

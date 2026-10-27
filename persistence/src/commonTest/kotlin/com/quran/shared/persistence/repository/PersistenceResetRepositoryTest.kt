package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.persistence.repository.note.NoteStore
import com.quran.shared.syncv2.Generation
import com.quran.shared.syncv2.ItemFailure
import com.quran.shared.syncv2.Outbox
import com.quran.shared.syncv2.SyncRow
import com.quran.shared.syncv2.SyncStore
import com.quran.shared.syncv2.SyncSupersededException
import com.quran.shared.syncv2.types.NoteRange
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PersistenceResetRepositoryTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var reset: PersistenceResetRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        reset = PersistenceResetRepositoryImpl(database.synced)
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
        ReadingBookmarksRepositoryImpl(database.synced).setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 42, Mushaf.MADANI_1440, at(100))

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
        val store = SyncStore(database.synced)
        store.put("NOTE", SyncRow(1, "remote-note", JsonObject(emptyMap())))
        store.token = 42
        store.account = "account-id"
        store.outboxes = listOf(Outbox("account-id", 42, emptyList()))
        store.failures = mapOf("NOTE:1" to ItemFailure(500, 1))

        reset.deleteAllData()

        assertEquals(emptyList(), database.synced.sync_itemQueries.all("NOTE").executeAsList())
        assertEquals(0, store.token)
        assertNull(store.account)
        assertEquals(emptyList(), store.outboxes)
        assertEquals(emptyMap(), store.failures)
    }

    @Test
    fun `a sync that started before the reset can't write after it`() {
        val store = SyncStore(database.synced)
        store.account = "account-id"
        val inFlight = Generation(database.synced, store, store.epoch)

        reset.deleteAllData()

        assertFailsWith<SyncSupersededException> {
            inFlight.transaction {
                NoteStore(database.synced).insert("Downloaded by the sync", listOf(NoteRange(1, 1, 1, 7)), 100)
                store.token = 42
            }
        }
        assertEquals(emptyList(), database.synced.notesQueries.all().executeAsList())
        assertEquals(0, store.token)
    }

    @Test
    fun `reset keeps the import history`() {
        database.quran.import_trackingQueries.recordImportHistory("v1:handled")

        reset.deleteAllData()

        assertEquals(listOf("v1:handled"), database.quran.import_trackingQueries.getRecordedImportHistory(listOf("v1:handled")).executeAsList())
    }

    @Test
    fun `reset keeps the key-value store`() {
        database.quran.key_value_storeQueries.putValue("com.quran.sync.session", "kept")

        reset.deleteAllData()

        assertEquals("kept", database.quran.key_value_storeQueries.getValue("com.quran.sync.session").executeAsOne())
    }
}

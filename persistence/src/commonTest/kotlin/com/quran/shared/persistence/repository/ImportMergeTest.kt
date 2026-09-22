package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.ImportAyahHighlight
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ImportMergeTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: QuranDatabase
    private lateinit var repository: PersistenceImportRepositoryImpl

    @BeforeTest
    fun setup() {
        driver = TestDatabaseDriver().createDriver()
        database = QuranDatabase(driver)
        repository = PersistenceImportRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `duplicate empty collections choose dates deterministically and count one fingerprint`() = runTest {
        val data = PersistenceImportData(collections = listOf(
            ImportCollection("older", "Study", at(100), at(40)),
            ImportCollection("newer", "Study", at(200), at(50)),
            ImportCollection("earliest", "Study", at(200), at(30))
        ))

        val first = repository.importData(data)
        val collection = database.collectionsQueries.getCollectionByName("Study").executeAsOne()
        assertEquals(1, first.collectionsImported)
        assertEquals(0, first.matched)
        assertEquals(30L, collection.created_at)
        assertEquals(200L, collection.modified_at)
    }

    @Test
    fun `duplicate reading bookmark content applies newest timestamp only once`() = runTest {
        val data = PersistenceImportData(readingBookmarks = listOf(
            ImportReadingBookmark.Page(42, at(200), ReadingBookmarkSlot.GREEN),
            ImportReadingBookmark.Page(42, at(100), ReadingBookmarkSlot.GREEN)
        ))

        val first = repository.importData(data)
        val slot = database.reading_bookmarksQueries.getReadingBookmarkForSlot(1).executeAsOne()
        assertEquals(1, first.readingBookmarksImported)
        assertEquals(200L, slot.modified_at)
        assertEquals(2L, slot.pending_version)
    }

    @Test
    fun `import accepts the same raw values as repository writes`() = runTest {
        val result = repository.importData(
            PersistenceImportData(
                collections = listOf(
                    ImportCollection("blank-name", "", at(100)),
                    ImportCollection("", "Ignored duplicate ID", at(100)),
                    ImportCollection("", "Selected duplicate ID", at(200))
                ),
                collectionBookmarks = listOf(
                    ImportCollectionAyahBookmark("", 0, 0, at(200))
                ),
                readingSessions = listOf(ImportReadingSession(115, 0, at(200))),
                notes = listOf(ImportNote("", 115, 1, -1, 0, at(200))),
                highlights = listOf(
                    ImportAyahHighlight(-1, -1, AyahHighlightColor.BLUE, at(200))
                )
            )
        )

        assertTrue(result.changed)
        assertEquals(2, result.collectionsImported)
        assertEquals(2, result.bookmarksImported)
        assertEquals(1, result.collectionBookmarksImported)
        assertEquals(1, result.readingSessionsImported)
        assertEquals(1, result.notesImported)
        assertEquals(1, result.highlightsImported)
        assertEquals("", database.collectionsQueries.getCollectionByName("").executeAsOne().name)
        assertEquals(
            "Selected duplicate ID",
            database.collectionsQueries.getCollectionByName("Selected duplicate ID").executeAsOne().name
        )
        assertEquals(null, database.collectionsQueries.getCollectionByName("Ignored duplicate ID").executeAsOneOrNull())
        assertEquals("", database.notesQueries.getNotes().executeAsOne().note)
        assertEquals(
            115L to 0L,
            database.reading_sessionsQueries.getReadingSessions().executeAsOne().let {
                it.chapter_number to it.verse_number
            }
        )
        assertTrue(database.bookmarksQueries.getBookmarkForAyah(0, 0).executeAsOne().local_id > 0)
        assertTrue(database.bookmarksQueries.getBookmarkForAyah(-1, -1).executeAsOne().local_id > 0)
    }

    @Test
    fun `direct memberships share parents resolve Favorites`() = runTest {
        val first = repository.importData(
            membershipData(favoritesId = "favorite-a", customId = "custom-a")
        )

        assertEquals(1, first.bookmarksImported)
        assertEquals(1, first.collectionsImported)
        assertEquals(2, first.collectionBookmarksImported)
        assertEquals(1L, database.bookmarksQueries.countAll().executeAsOne())
        assertEquals(2L, database.bookmark_collectionsQueries.countAll().executeAsOne())
    }

    @Test
    fun `reactivating a retained membership is a mutation but not an insertion`() = runTest {
        val data = PersistenceImportData(
            collections = listOf(ImportCollection("study", "Study", at(100))),
            collectionBookmarks = listOf(
                ImportCollectionAyahBookmark("study", 2, 255, at(100))
            )
        )
        repository.importData(data)
        val bookmark = database.bookmarksQueries.getBookmarkForAyah(2, 255).executeAsOne()
        val collection = database.collectionsQueries.getCollectionByName("Study").executeAsOne()
        var link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(bookmark.local_id, collection.local_id)
            .executeAsOne()
        database.bookmark_collectionsQueries.persistRemoteBookmarkCollection(
            bookmark_local_id = bookmark.local_id,
            collection_local_id = collection.local_id,
            bookmark_remote_id = "remote-bookmark",
            collection_remote_id = "remote-collection",
            created_at = link.created_at,
            modified_at = link.modified_at
        )
        database.bookmark_collectionsQueries.clearLocalMutationFor(
            bookmark_remote_id = "remote-bookmark",
            collection_remote_id = "remote-collection",
            modified_at = link.modified_at,
            id = link.local_id,
            pending_op = link.pending_op,
            pending_version = link.pending_version
        )
        database.bookmark_collectionsQueries.markBookmarkCollectionDeleted(
            bookmark_local_id = bookmark.local_id,
            collection_local_id = collection.local_id,
            timestamp = 200
        )
        link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(bookmark.local_id, collection.local_id)
            .executeAsOne()
        assertEquals(0L, link.is_active)

        val result = repository.importData(data, deleteExisting = false)

        assertEquals(0, result.collectionBookmarksImported)
        assertTrue(result.changed)
        assertEquals(
            1L,
            database.bookmark_collectionsQueries
                .getCollectionBookmarkFor(bookmark.local_id, collection.local_id)
                .executeAsOne()
                .is_active
        )
    }

    @Test
    fun `newest highlight candidate wins`() = runTest {
        val data = PersistenceImportData(
            highlights = listOf(
                ImportAyahHighlight(2, 255, AyahHighlightColor.BLUE, at(100)),
                ImportAyahHighlight(2, 255, AyahHighlightColor.YELLOW, at(200))
            )
        )

        val first = repository.importData(data)
        assertEquals(1, first.highlightsImported)
        assertEquals(1, first.keptExisting)
        val highlightLink = database.bookmark_collectionsQueries.getCollectionBookmarksWithDetails()
            .executeAsList()
            .single()
        assertEquals("system:highlights:yellow", highlightLink.collection_name)
    }

    private fun membershipData(favoritesId: String, customId: String) = PersistenceImportData(
        collections = listOf(
            ImportCollection(favoritesId, " favorites ", at(200), at(50)),
            ImportCollection(customId, "Study", at(300), at(75))
        ),
        collectionBookmarks = listOf(
            ImportCollectionAyahBookmark(favoritesId, 2, 255, at(200), at(50)),
            ImportCollectionAyahBookmark(customId, 2, 255, at(300), at(75))
        )
    )

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis).toPlatform()
}

package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TrackedImportTest {
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

        val first = repository.importData(data, false, true)
        val collection = database.collectionsQueries.getCollectionByName("Study").executeAsOne()
        assertEquals(1, first.collectionsImported)
        assertEquals(0, first.matched)
        assertEquals(30L, collection.created_at)
        assertEquals(200L, collection.modified_at)

        val replay = repository.importData(data.copy(collections = data.collections.reversed()), false, true)
        assertEquals(1, replay.alreadyProcessed)
        assertFalse(replay.changed)
    }

    @Test
    fun `duplicate reading bookmark content applies newest timestamp only once`() = runTest {
        val data = PersistenceImportData(readingBookmarks = listOf(
            ImportReadingBookmark.Page(42, at(200), ReadingBookmarkSlot.GREEN),
            ImportReadingBookmark.Page(42, at(100), ReadingBookmarkSlot.GREEN)
        ))

        val first = repository.importData(data, false, true)
        val slot = database.reading_bookmarksQueries.getReadingBookmarkForSlot(1).executeAsOne()
        assertEquals(1, first.readingBookmarksImported)
        assertEquals(200L, slot.modified_at)
        assertEquals(2L, slot.pending_version)

        val replay = repository.importData(data, false, true)
        assertEquals(1, replay.alreadyProcessed)
        assertFalse(replay.changed)
        assertEquals(slot, database.reading_bookmarksQueries.getReadingBookmarkForSlot(1).executeAsOne())
    }

    @Test
    fun `empty tracked import is applied without changes`() = runTest {
        val result = repository.importData(PersistenceImportData(), deleteExisting = false, trackHistory = true)

        assertFalse(result.changed)
        assertEquals(0L, database.notesQueries.countAll().executeAsOne())
    }

    @Test
    fun `tracked history prevents deleted content replay and allows changed content`() = runTest {
        val first = repository.importData(noteData("Original note"), false, true)
        assertEquals(1, first.notesImported)
        val note = database.notesQueries.getNotes().executeAsOne()
        database.notesQueries.deleteNote(timestamp = 200, id = note.local_id)

        val replay = repository.importData(noteData("  Original   note  "), false, true)
        assertEquals(1, replay.alreadyProcessed)
        assertEquals(0, replay.notesImported)
        assertEquals(emptyList(), database.notesQueries.getNotes().executeAsList())

        val changed = repository.importData(noteData("Changed note"), false, true)
        assertEquals(1, changed.notesImported)
        assertEquals(listOf("Changed note"), database.notesQueries.getNotes().executeAsList().map { it.note })
    }

    @Test
    fun `direct memberships share parents resolve Favorites and ignore reference id changes`() = runTest {
        val first = repository.importData(
            membershipData(favoritesId = "favorite-a", customId = "custom-a"),
            false,
            true
        )

        assertEquals(1, first.bookmarksImported)
        assertEquals(1, first.collectionsImported)
        assertEquals(2, first.collectionBookmarksImported)
        assertEquals(1L, database.bookmarksQueries.countAll().executeAsOne())
        assertEquals(2L, database.bookmark_collectionsQueries.countAll().executeAsOne())

        val replay = repository.importData(
            membershipData(favoritesId = "favorite-b", customId = "custom-b"),
            false,
            true
        )
        assertEquals(2, replay.alreadyProcessed)
        assertFalse(replay.changed)
        assertEquals(1L, database.bookmarksQueries.countAll().executeAsOne())
    }

    @Test
    fun `newest highlight candidate wins and all colors become handled`() = runTest {
        val data = PersistenceImportData(
            highlights = listOf(
                ImportAyahHighlight(2, 255, AyahHighlightColor.BLUE, at(100)),
                ImportAyahHighlight(2, 255, AyahHighlightColor.YELLOW, at(200))
            )
        )

        val first = repository.importData(data, false, true)
        assertEquals(1, first.highlightsImported)
        assertEquals(1, first.keptExisting)
        val highlightLink = database.bookmark_collectionsQueries.getCollectionBookmarksWithDetails()
            .executeAsList()
            .single()
        assertEquals("system:highlights:yellow", highlightLink.collection_name)

        val replay = repository.importData(data, false, true)
        assertEquals(2, replay.alreadyProcessed)
        assertFalse(replay.changed)
    }

    @Test
    fun `invalid replacement tracking combination performs no writes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            repository.importData(noteData("Never written"), deleteExisting = true, trackHistory = true)
        }
        assertEquals(0L, database.notesQueries.countAll().executeAsOne())
    }

    @Test
    fun `replacement preserves import history`() = runTest {
        repository.importData(noteData("Handled"), deleteExisting = false, trackHistory = true)

        val replacement = repository.importData(
            noteData("Replacement"),
            deleteExisting = true,
            trackHistory = false
        )

        assertTrue(replacement.changed)

        val replay = repository.importData(noteData("Handled"), deleteExisting = false, trackHistory = true)
        assertEquals(1, replay.alreadyProcessed)
        assertEquals(listOf("Replacement"), database.notesQueries.getNotes().executeAsList().map { it.note })
    }

    @Test
    fun `managed reset clears import history`() = runTest {
        repository.importData(noteData("Imported"), false, true)

        PersistenceResetRepositoryImpl(database).deleteAllData()

        assertEquals(0L, database.notesQueries.countAll().executeAsOne())
        assertEquals(6L, database.collectionsQueries.countAll().executeAsOne())

        val replay = repository.importData(noteData("Imported"), false, true)
        assertEquals(0, replay.alreadyProcessed)
        assertEquals(1, replay.notesImported)
    }

    @Test
    fun `history lookup chunks snapshots larger than the SQLite bind limit`() = runTest {
        val notes = (0..900).map { index ->
            ImportNote("Note $index", 2, 1, 2, 1, at(100), at(50))
        }
        val data = PersistenceImportData(notes = notes)

        val first = repository.importData(data, deleteExisting = false, trackHistory = true)
        val replay = repository.importData(data, deleteExisting = false, trackHistory = true)

        assertEquals(901, first.notesImported)
        assertEquals(901, replay.alreadyProcessed)
        assertFalse(replay.changed)
    }

    @Test
    fun `failure after recording history rolls back targets and history`() = runTest {
        driver.execute(null, "UPDATE collection SET is_default = 0 WHERE is_default = 1", 0)
        val data = PersistenceImportData(
            collections = listOf(
                ImportCollection("study", "Study", at(100)),
                ImportCollection("favorites", "Favorites", at(100))
            ),
            collectionBookmarks = listOf(ImportCollectionAyahBookmark("favorites", 2, 255, at(100)))
        )

        assertFailsWith<IllegalArgumentException> {
            repository.importData(data, deleteExisting = false, trackHistory = true)
        }

        assertNull(database.collectionsQueries.getCollectionByName("Study").executeAsOneOrNull())
        driver.execute(null, "UPDATE collection SET is_default = 1 WHERE name = 'Favorites'", 0)
        val retry = repository.importData(data, deleteExisting = false, trackHistory = true)
        assertEquals(0, retry.alreadyProcessed)
        assertEquals(1, retry.collectionsImported)
    }

    @Test
    fun `cancellation after recording history rolls back targets and history`() = runTest {
        val importJob = Job()
        val cancellingRepository = PersistenceImportRepositoryImpl(
            QuranDatabase(CancelOnHistoryWriteDriver(driver) { importJob.cancel() })
        )
        val data = PersistenceImportData(
            notes = listOf(
                ImportNote("First", 2, 1, 2, 1, at(100)),
                ImportNote("Second", 2, 2, 2, 2, at(100))
            )
        )

        assertFailsWith<CancellationException> {
            withContext(importJob) {
                cancellingRepository.importData(data, deleteExisting = false, trackHistory = true)
            }
        }

        assertEquals(0L, database.notesQueries.countAll().executeAsOne())
        val retry = repository.importData(data, deleteExisting = false, trackHistory = true)
        assertEquals(0, retry.alreadyProcessed)
        assertEquals(2, retry.notesImported)
    }

    @Test
    fun `renamed destination keeps handled memberships while new content recreates its name`() = runTest {
        val handled = oldPageBookmarks(2 to 255)
        repository.importData(handled, deleteExisting = false, trackHistory = true)
        val original = database.collectionsQueries.getCollectionByName(OLD_PAGE_BOOKMARKS).executeAsOne()
        database.collectionsQueries.updateCollectionName(name = "Renamed", timestamp = 500, id = original.local_id)

        val replay = repository.importData(handled, deleteExisting = false, trackHistory = true)
        assertEquals(1, replay.alreadyProcessed)
        assertFalse(replay.changed)
        assertNull(database.collectionsQueries.getCollectionByName(OLD_PAGE_BOOKMARKS).executeAsOneOrNull())

        val withNewContent = repository.importData(
            oldPageBookmarks(2 to 255, 3 to 1),
            deleteExisting = false,
            trackHistory = true
        )
        val recreated = database.collectionsQueries.getCollectionByName(OLD_PAGE_BOOKMARKS).executeAsOne()
        assertEquals(1, withNewContent.alreadyProcessed)
        assertEquals(1, withNewContent.collectionsImported)
        assertEquals(1, withNewContent.collectionBookmarksImported)
        assertEquals(listOf(3L to 1L), membersOf(recreated.local_id))
        assertEquals(listOf(2L to 255L), membersOf(original.local_id))
    }

    private fun oldPageBookmarks(vararg ayahs: Pair<Int, Int>) = PersistenceImportData(
        collections = listOf(ImportCollection("old-pages", OLD_PAGE_BOOKMARKS, at(100))),
        collectionBookmarks = ayahs.map { (sura, ayah) ->
            ImportCollectionAyahBookmark("old-pages", sura, ayah, at(100))
        }
    )

    private fun membersOf(collectionLocalId: Long) =
        database.bookmark_collectionsQueries.getCollectionBookmarksWithDetails().executeAsList()
            .filter { it.collection_local_id == collectionLocalId }
            .map { it.sura to it.ayah }

    private fun noteData(body: String) = PersistenceImportData(
        notes = listOf(ImportNote(body, 2, 1, 2, 3, at(100), at(50)))
    )

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

private const val OLD_PAGE_BOOKMARKS = "Old Page Bookmarks"

/** Cancels the import job once history is written, so cancellation lands after target writes. */
private class CancelOnHistoryWriteDriver(
    private val delegate: SqlDriver,
    private val onHistoryWrite: () -> Unit
) : SqlDriver by delegate {
    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult<Long> {
        val result = delegate.execute(identifier, sql, parameters, binders)
        if (sql.contains("INTO import_history")) onHistoryWrite()
        return result
    }
}

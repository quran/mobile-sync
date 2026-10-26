package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.at
import com.quran.shared.persistence.input.ImportAyahHighlight
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.model.AyahHighlight
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackedImportTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: MobileSyncDatabase
    private lateinit var repository: PersistenceImportRepositoryImpl

    @BeforeTest
    fun setup() {
        driver = TestDatabaseDriver().createDriver()
        database = MobileSyncDatabase(driver)
        repository = PersistenceImportRepositoryImpl(database.quran, database.synced)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `duplicate empty collections choose dates deterministically and count one fingerprint`() = runTest {
        val data = PersistenceImportData(collections = listOf(
            ImportCollection("Study", at(100), at(40)),
            ImportCollection("Study", at(200), at(50)),
            ImportCollection("Study", at(200), at(30))
        ))

        val first = repository.importData(data, false, true)
        val collection = collectionByName("Study")!!
        assertEquals(1, first.collectionsImported)
        assertEquals(0, first.matched)
        assertEquals(30L, collection.created_at)
        assertEquals(200L, collection.updated_at)

        val replay = repository.importData(data.copy(collections = data.collections.reversed()), false, true)
        assertEquals(1, replay.alreadyProcessed)
        assertFalse(replay.changed)
    }

    @Test
    fun `duplicate reading bookmark content applies newest timestamp only once`() = runTest {
        val data = PersistenceImportData(readingBookmarks = listOf(
            ImportReadingBookmark.Page(42, Mushaf.QCF_V2, at(200), ReadingBookmarkSlot.TEAL),
            ImportReadingBookmark.Page(42, Mushaf.QCF_V2, at(100), ReadingBookmarkSlot.TEAL)
        ))

        val first = repository.importData(data, false, true)
        val slot = database.synced.readingBookmarksQueries.bySlot(1).executeAsOne()
        assertEquals(1, first.readingBookmarksImported)
        assertEquals(200L, slot.updated_at)

        val replay = repository.importData(data, false, true)
        assertEquals(1, replay.alreadyProcessed)
        assertFalse(replay.changed)
        assertEquals(slot, database.synced.readingBookmarksQueries.bySlot(1).executeAsOne())
    }

    @Test
    fun `empty tracked import is applied without changes`() = runTest {
        val result = repository.importData(PersistenceImportData(), deleteExisting = false, trackHistory = true)

        assertFalse(result.changed)
        assertEquals(emptyList(), noteBodies())
    }

    @Test
    fun `tracked history prevents deleted content replay and allows changed content`() = runTest {
        val first = repository.importData(noteData("Original note"), false, true)
        assertEquals(1, first.notesImported)
        val notes = NotesRepositoryImpl(database.synced)
        notes.deleteNote(notes.getAllNotes().single().id)

        val replay = repository.importData(noteData("  Original   note  "), false, true)
        assertEquals(1, replay.alreadyProcessed)
        assertEquals(0, replay.notesImported)
        assertEquals(emptyList(), noteBodies())

        val changed = repository.importData(noteData("Changed note"), false, true)
        assertEquals(1, changed.notesImported)
        assertEquals(listOf("Changed note"), noteBodies())
    }

    @Test
    fun `direct memberships share parents and resolve Favorites by canonical name`() = runTest {
        val first = repository.importData(membershipData(collectionName = " favorites ", membershipName = "FAVORITES"), false, true)

        assertEquals(1, first.bookmarksImported)
        assertEquals(1, first.collectionsImported)
        assertEquals(2, first.collectionBookmarksImported)
        assertEquals(2, database.synced.collectionsQueries.collectionAyahsAt(2, 255).executeAsList().size)
        val favorites = database.synced.collectionsQueries.collectionDefault().executeAsOne()
        assertEquals(listOf(2L to 255L), membersOf(favorites.local_id))

        val replay = repository.importData(membershipData(collectionName = "Favorites", membershipName = "favorites\t"), false, true)
        assertEquals(2, replay.alreadyProcessed)
        assertFalse(replay.changed)
        assertEquals(2, database.synced.collectionsQueries.collectionAyahsAll().executeAsList().size)
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
        assertEquals(
            listOf(AyahHighlight(2, 255, AyahHighlightColor.YELLOW, at(200))),
            CollectionBookmarksRepositoryImpl(database.synced).getHighlightsFlow().first()
        )

        val replay = repository.importData(data, false, true)
        assertEquals(2, replay.alreadyProcessed)
        assertFalse(replay.changed)
    }

    @Test
    fun `invalid replacement tracking combination performs no writes`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            repository.importData(noteData("Never written"), deleteExisting = true, trackHistory = true)
        }
        assertEquals(emptyList(), noteBodies())
    }

    @Test
    fun `replacement preserves import history`() = runTest {
        repository.importData(noteData("Handled"), deleteExisting = false, trackHistory = true)

        val replacement = repository.importData(noteData("Replacement"), deleteExisting = true, trackHistory = false)

        assertTrue(replacement.changed)
        val replay = repository.importData(noteData("Handled"), deleteExisting = false, trackHistory = true)
        assertEquals(1, replay.alreadyProcessed)
        assertEquals(listOf("Replacement"), noteBodies())
    }

    @Test
    fun `a tracked import after a reset skips what an earlier import handled`() = runTest {
        repository.importData(noteData("Imported"), false, true)

        PersistenceResetRepositoryImpl(database.synced).deleteAllData()

        assertEquals(emptyList(), noteBodies())
        assertEquals(1, database.synced.collectionsQueries.collectionsAll().executeAsList().size)
        val replay = repository.importData(noteData("Imported"), false, true)
        assertEquals(1, replay.alreadyProcessed)
        assertEquals(0, replay.notesImported)
        assertEquals(emptyList(), noteBodies())
    }

    @Test
    fun `history lookup chunks snapshots larger than the SQLite bind limit`() = runTest {
        val data = PersistenceImportData(notes = (0..900).map { index -> ImportNote("Note $index", 2, 1, 2, 1, at(100), at(50)) })

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
            collections = listOf(ImportCollection("Study", at(100)), ImportCollection("Favorites", at(100))),
            collectionBookmarks = listOf(ImportCollectionAyahBookmark("Favorites", 2, 255, at(100)))
        )

        // With no default collection, resolving Favorites fails after Study was written and recorded.
        assertFails { repository.importData(data, deleteExisting = false, trackHistory = true) }

        assertNull(collectionByName("Study"))
        driver.execute(null, "UPDATE collection SET is_default = 1 WHERE name = 'Favorites'", 0)
        val retry = repository.importData(data, deleteExisting = false, trackHistory = true)
        assertEquals(0, retry.alreadyProcessed)
        assertEquals(1, retry.collectionsImported)
    }

    @Test
    fun `cancellation after recording history rolls back targets and history`() = runTest {
        val importJob = Job()
        val cancelling = MobileSyncDatabase(CancelOnHistoryWriteDriver(driver) { importJob.cancel() })
        val cancellingRepository = PersistenceImportRepositoryImpl(cancelling.quran, cancelling.synced)
        val data = PersistenceImportData(
            notes = listOf(ImportNote("First", 2, 1, 2, 1, at(100)), ImportNote("Second", 2, 2, 2, 2, at(100)))
        )

        assertFailsWith<CancellationException> {
            withContext(importJob) { cancellingRepository.importData(data, deleteExisting = false, trackHistory = true) }
        }

        assertEquals(emptyList(), noteBodies())
        val retry = repository.importData(data, deleteExisting = false, trackHistory = true)
        assertEquals(0, retry.alreadyProcessed)
        assertEquals(2, retry.notesImported)
    }

    @Test
    fun `renamed destination keeps handled memberships while new content recreates its name`() = runTest {
        val handled = oldPageBookmarks(2 to 255)
        repository.importData(handled, deleteExisting = false, trackHistory = true)
        val original = collectionByName(OLD_PAGE_BOOKMARKS)!!
        CollectionsRepositoryImpl(database.synced).updateCollection(original.local_id.toString(), "Renamed", at(500))

        val replay = repository.importData(handled, deleteExisting = false, trackHistory = true)
        assertEquals(1, replay.alreadyProcessed)
        assertFalse(replay.changed)
        assertNull(collectionByName(OLD_PAGE_BOOKMARKS))

        val withNewContent = repository.importData(oldPageBookmarks(2 to 255, 3 to 1), deleteExisting = false, trackHistory = true)
        val recreated = collectionByName(OLD_PAGE_BOOKMARKS)!!
        assertEquals(1, withNewContent.alreadyProcessed)
        assertEquals(1, withNewContent.collectionsImported)
        assertEquals(1, withNewContent.collectionBookmarksImported)
        assertEquals(listOf(3L to 1L), membersOf(recreated.local_id))
        assertEquals(listOf(2L to 255L), membersOf(original.local_id))
    }

    private fun oldPageBookmarks(vararg ayahs: Pair<Int, Int>) = PersistenceImportData(
        collections = listOf(ImportCollection(OLD_PAGE_BOOKMARKS, at(100))),
        collectionBookmarks = ayahs.map { (sura, ayah) -> ImportCollectionAyahBookmark(OLD_PAGE_BOOKMARKS, sura, ayah, at(100)) }
    )

    private fun collectionByName(name: String) =
        database.synced.collectionsQueries.collectionByKey(name.lowercase()).executeAsOneOrNull()

    private fun membersOf(collectionLocalId: Long) =
        database.synced.collectionsQueries.collectionAyahsIn(collectionLocalId).executeAsList().map { it.sura to it.ayah }

    private fun noteBodies() = database.synced.notesQueries.all().executeAsList().map { it.body }

    private fun noteData(body: String) = PersistenceImportData(notes = listOf(ImportNote(body, 2, 1, 2, 3, at(100), at(50))))

    private fun membershipData(collectionName: String, membershipName: String) = PersistenceImportData(
        collections = listOf(ImportCollection(collectionName, at(200), at(50)), ImportCollection("Study", at(300), at(75))),
        collectionBookmarks = listOf(
            ImportCollectionAyahBookmark(membershipName, 2, 255, at(200), at(50)),
            ImportCollectionAyahBookmark("Study", 2, 255, at(300), at(75))
        )
    )
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

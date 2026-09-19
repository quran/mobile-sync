package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.RemoteReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class ReadingBookmarksImportTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: QuranDatabase
    private lateinit var repository: PersistenceImportRepositoryImpl
    private lateinit var readingBookmarks: ReadingBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        driver = TestDatabaseDriver().createDriver()
        database = QuranDatabase(driver)
        repository = PersistenceImportRepositoryImpl(database)
        readingBookmarks = ReadingBookmarksRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `imports mixed reading bookmarks by slot with names timestamps and pending creates`() = runTest {
        val result = repository.importData(
            PersistenceImportData(
                readingBookmarks = listOf(
                    ImportReadingBookmark.Page(604, at(300), ReadingBookmarkSlot.INDIGO),
                    ImportReadingBookmark.Ayah(2, 255, at(100), ReadingBookmarkSlot.CORAL, "Daily"),
                    ImportReadingBookmark.Ayah(2, 255, at(200), ReadingBookmarkSlot.TEAL, "Study")
                )
            )
        )

        val rows = database.reading_bookmarksQueries.getReadingBookmarks().executeAsList()
        assertEquals(3, result.readingBookmarksImported)
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.slot })
        assertEquals(listOf("AYAH", "AYAH", "PAGE"), rows.map { it.bookmark_type })
        assertEquals(listOf("Daily", "Study", null), rows.map { it.name })
        assertEquals(listOf(100L, 200L, 300L), rows.map { it.modified_at })
        assertEquals(rows.map { it.modified_at }, rows.map { it.created_at })
        assertEquals(listOf(2L, 2L, null), rows.map { it.sura })
        assertEquals(listOf(255L, 255L, null), rows.map { it.ayah })
        assertEquals(listOf(null, null, 604L), rows.map { it.page })
        assertEquals(listOf(1L, 1L, 1L), rows.map { it.mushaf_id })
        assertEquals(List(3) { Mutation.CREATED }, readingBookmarks.fetchMutatedReadingBookmarks().map { it.mutation })
        assertEquals(0L, database.bookmarksQueries.countAll().executeAsOne())
    }

    @Test
    fun `merge replaces supplied slots and names while preserving identities and other slots`() = runTest {
        persistRemoteSlot(1, "Coral")
        persistRemoteSlot(2, "Teal")
        readingBookmarks.setPageReadingBookmark(ReadingBookmarkSlot.INDIGO, 88, at(100))
        val before = database.reading_bookmarksQueries.getReadingBookmarks().executeAsList()
        val data = PersistenceImportData(
            readingBookmarks = listOf(
                ImportReadingBookmark.Ayah(18, 10, at(200), ReadingBookmarkSlot.CORAL, "Imported"),
                ImportReadingBookmark.Page(1, at(300), ReadingBookmarkSlot.TEAL)
            )
        )

        repository.importData(data)
        repository.importData(data)

        val after = database.reading_bookmarksQueries.getReadingBookmarks().executeAsList()
        assertEquals(before.map { it.local_id }, after.map { it.local_id })
        assertEquals(before.map { it.remote_id }, after.map { it.remote_id })
        assertEquals(before.map { it.created_at }, after.map { it.created_at })
        assertEquals(before[2], after[2])
        assertEquals("AYAH", after[0].bookmark_type)
        assertEquals(18L, after[0].sura)
        assertEquals(10L, after[0].ayah)
        assertNull(after[0].page)
        assertEquals("Imported", after[0].name)
        assertEquals(200L, after[0].modified_at)
        assertEquals(1L, after[1].page)
        assertNull(after[1].name)
        assertEquals(300L, after[1].modified_at)
        assertEquals(listOf("MODIFIED", "MODIFIED"), after.take(2).map { it.pending_op })
    }

    @Test
    fun `replacement restores supplied slots and syncs clearing of omitted slots`() = runTest {
        persistRemoteSlot(1, "Coral")
        persistRemoteSlot(2, "Teal")
        readingBookmarks.setAyahReadingBookmark(ReadingBookmarkSlot.INDIGO, 2, 255, at(100))
        val before = database.reading_bookmarksQueries.getReadingBookmarks().executeAsList()

        val result = repository.importData(
            PersistenceImportData(
                readingBookmarks = listOf(
                    ImportReadingBookmark.Page(42, at(200), ReadingBookmarkSlot.CORAL, "Restored")
                )
            ),
            deleteExisting = true
        )

        val after = database.reading_bookmarksQueries.getReadingBookmarks().executeAsList()
        assertEquals(1, result.readingBookmarksImported)
        assertEquals(before.map { it.local_id }, after.map { it.local_id })
        assertEquals(before.map { it.remote_id }, after.map { it.remote_id })
        assertEquals("Restored", after[0].name)
        assertEquals(42L, after[0].page)
        assertEquals(200L, after[0].modified_at)
        after.drop(1).forEach {
            assertNull(it.bookmark_type)
            assertNull(it.sura)
            assertNull(it.ayah)
            assertNull(it.page)
            assertNull(it.mushaf_id)
        }
        assertEquals(listOf("MODIFIED", "MODIFIED", "CREATED"), after.map { it.pending_op })
    }

    @Test
    fun `duplicate slots and invalid pages reject replacement before any writes`() = runTest {
        persistRemoteSlot(1, "Keep")
        val before = database.reading_bookmarksQueries.getReadingBookmarks().executeAsList()
        val invalidBookmarks = listOf(
            listOf(
                ImportReadingBookmark.Ayah(2, 255, at(200), ReadingBookmarkSlot.CORAL),
                ImportReadingBookmark.Page(42, at(200), ReadingBookmarkSlot.CORAL)
            ),
            listOf(ImportReadingBookmark.Page(0, at(200), ReadingBookmarkSlot.TEAL)),
            listOf(ImportReadingBookmark.Page(605, at(200), ReadingBookmarkSlot.TEAL))
        )

        invalidBookmarks.forEach { bookmarks ->
            assertFailsWith<IllegalArgumentException> {
                repository.importData(
                    PersistenceImportData(
                        readingSessions = listOf(ImportReadingSession(18, 10, at(200))),
                        readingBookmarks = bookmarks
                    ),
                    deleteExisting = true
                )
            }
            assertEquals(before, database.reading_bookmarksQueries.getReadingBookmarks().executeAsList())
            assertEquals(emptyList(), database.reading_sessionsQueries.getReadingSessions().executeAsList())
        }
    }

    @Test
    fun `empty merge leaves reading bookmarks unchanged and reports zero`() = runTest {
        persistRemoteSlot(1, "Keep")
        val before = database.reading_bookmarksQueries.getReadingBookmarks().executeAsList()

        val result = repository.importData(PersistenceImportData())

        assertEquals(0, result.readingBookmarksImported)
        assertEquals(before, database.reading_bookmarksQueries.getReadingBookmarks().executeAsList())
    }

    @Test
    fun `stale create acknowledgment preserves imported slot changes`() = runTest {
        readingBookmarks.setPageReadingBookmark(ReadingBookmarkSlot.CORAL, 1, at(100))
        val staleCreate = readingBookmarks.fetchMutatedReadingBookmarks().single()
        repository.importData(
            PersistenceImportData(
                readingBookmarks = listOf(
                    ImportReadingBookmark.Ayah(18, 10, at(200), ReadingBookmarkSlot.CORAL, "Imported")
                )
            )
        )

        readingBookmarks.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingBookmark(
                        slot = 1,
                        name = null,
                        type = "PAGE",
                        sura = null,
                        ayah = null,
                        page = 1,
                        lastUpdated = at(100),
                        createdAt = at(100)
                    ),
                    remoteID = "remote-coral",
                    mutation = Mutation.CREATED,
                    ack = staleCreate.ack
                )
            ),
            localMutationsToClear = listOf(staleCreate),
            writeBoundaryGuard = PersistenceWriteBoundaryGuard.Allow
        )

        val row = database.reading_bookmarksQueries.getReadingBookmarkForSlot(1).executeAsOne()
        assertEquals(staleCreate.localID, row.local_id.toString())
        assertEquals("remote-coral", row.remote_id)
        assertEquals("Imported", row.name)
        assertEquals("AYAH", row.bookmark_type)
        assertEquals(18L, row.sura)
        assertEquals(10L, row.ayah)
        assertEquals(200L, row.modified_at)
        assertEquals(Mutation.MODIFIED, readingBookmarks.fetchMutatedReadingBookmarks().single().mutation)
    }

    private fun persistRemoteSlot(slot: Long, name: String) {
        database.reading_bookmarksQueries.persistRemoteReadingBookmark(
            remote_id = "remote-$slot",
            slot = slot,
            name = name,
            bookmark_type = "PAGE",
            sura = null,
            ayah = null,
            page = 88,
            mushaf_id = 1,
            created_at = 50,
            modified_at = 100
        )
    }

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis).toPlatform()
}

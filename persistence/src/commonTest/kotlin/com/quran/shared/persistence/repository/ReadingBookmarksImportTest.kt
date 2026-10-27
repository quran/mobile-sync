package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingBookmarksImportTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var repository: PersistenceImportRepositoryImpl
    private lateinit var readingBookmarks: ReadingBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        repository = PersistenceImportRepositoryImpl(database.quran, database.synced)
        readingBookmarks = ReadingBookmarksRepositoryImpl(database.synced)
    }

    @Test
    fun `imports mixed reading bookmarks by slot with names and timestamps`() = runTest {
        val result = repository.importData(
            PersistenceImportData(
                readingBookmarks = listOf(
                    ImportReadingBookmark.Page(604, Mushaf.MADANI_1440, at(300), ReadingBookmarkSlot.RED),
                    ImportReadingBookmark.Ayah(2, 255, at(100), ReadingBookmarkSlot.TEAL, "Daily"),
                    ImportReadingBookmark.Ayah(2, 255, at(200), ReadingBookmarkSlot.ORANGE, " Study ")
                )
            )
        )

        val rows = rows()
        assertEquals(3, result.readingBookmarksImported)
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.slot })
        assertEquals(listOf("ayah", "ayah", "page"), rows.map { it.location_type })
        assertEquals(listOf("Daily", "Study", null), rows.map { it.name })
        assertEquals(listOf(100L, 200L, 300L), rows.map { it.updated_at })
        assertEquals(rows.map { it.updated_at }, rows.map { it.created_at })
        assertEquals(listOf(2L, 2L, 604L), rows.map { it.location_key })
        assertEquals(listOf(255L, 255L, null), rows.map { it.location_verse })
        assertEquals(listOf(5L, 5L, 1L), rows.map { it.mushaf_id })
        assertEquals(emptyList(), database.synced.collectionsQueries.collectionAyahsAll().executeAsList())
    }

    @Test
    fun `merge replaces supplied slots and names while preserving identities and other slots`() = runTest {
        insertSlot(1, "Green")
        insertSlot(2, "Purple")
        readingBookmarks.setPageReadingBookmark(ReadingBookmarkSlot.RED, 88, Mushaf.MADANI_1440, at(100))
        val before = rows()
        val data = PersistenceImportData(
            readingBookmarks = listOf(
                ImportReadingBookmark.Ayah(18, 10, at(200), ReadingBookmarkSlot.TEAL, "Imported"),
                ImportReadingBookmark.Page(1, Mushaf.MADANI_1440, at(300), ReadingBookmarkSlot.ORANGE)
            )
        )

        repository.importData(data)
        repository.importData(data)

        val after = rows()
        assertEquals(before.map { it.local_id }, after.map { it.local_id })
        assertEquals(before.map { it.created_at }, after.map { it.created_at })
        assertEquals(before[2], after[2])
        assertEquals("ayah", after[0].location_type)
        assertEquals(18L, after[0].location_key)
        assertEquals(10L, after[0].location_verse)
        assertEquals("Imported", after[0].name)
        assertEquals(200L, after[0].updated_at)
        assertEquals("page", after[1].location_type)
        assertEquals(1L, after[1].location_key)
        assertNull(after[1].name)
        assertEquals(300L, after[1].updated_at)
    }

    @Test
    fun `replacement restores supplied slots and clears omitted slots keeping their names`() = runTest {
        insertSlot(1, "Green")
        insertSlot(2, "Purple")
        readingBookmarks.setAyahReadingBookmark(ReadingBookmarkSlot.RED, 2, 255, at(100))
        val before = rows()

        val result = repository.importData(
            PersistenceImportData(readingBookmarks = listOf(ImportReadingBookmark.Page(42, Mushaf.MADANI_1440, at(200), ReadingBookmarkSlot.TEAL, "Restored"))),
            deleteExisting = true
        )

        val after = rows()
        assertEquals(1, result.readingBookmarksImported)
        assertEquals(before.map { it.local_id }, after.map { it.local_id })
        assertEquals("Restored", after[0].name)
        assertEquals(42L, after[0].location_key)
        assertEquals(200L, after[0].updated_at)
        assertEquals(listOf("Purple", null), after.drop(1).map { it.name })
        after.drop(1).forEach {
            assertNull(it.location_type)
            assertNull(it.location_key)
            assertNull(it.location_verse)
            assertNull(it.mushaf_id)
        }
    }

    @Test
    fun `duplicate slots use normal setter behavior`() = runTest {
        val result = repository.importData(
            PersistenceImportData(
                readingBookmarks = listOf(
                    ImportReadingBookmark.Ayah(2, 255, at(200), ReadingBookmarkSlot.TEAL),
                    ImportReadingBookmark.Page(1, Mushaf.MADANI_1440, at(300), ReadingBookmarkSlot.TEAL)
                )
            )
        )

        val row = rows().single()
        assertEquals(2, result.readingBookmarksImported)
        assertEquals("page" to 1L, row.location_type to row.location_key)
        assertEquals(300L, row.updated_at)
    }

    @Test
    fun `an imported page is stored like the setter stores it`() = runTest {
        repository.importData(
            PersistenceImportData(
                readingBookmarks = listOf(
                    ImportReadingBookmark.Page(537, Mushaf.INDOPAK_15_LINES, at(300), ReadingBookmarkSlot.TEAL),
                    ImportReadingBookmark.Page(584, Mushaf.MADANI_1405, at(300), ReadingBookmarkSlot.ORANGE),
                    ImportReadingBookmark.Page(250, Mushaf.SHEMERLY, at(300), ReadingBookmarkSlot.RED)
                )
            )
        )

        assertEquals(
            listOf(536L to 6L, 584L to 2L, 302L to 1L),
            rows().map { it.location_key to it.mushaf_id }
        )
    }

    @Test
    fun `an imported page outside its mushaf is skipped and the rest is imported`() = runTest {
        val result = repository.importData(
            PersistenceImportData(
                readingBookmarks = listOf(
                    ImportReadingBookmark.Page(300, Mushaf.MADANI_1440, at(200), ReadingBookmarkSlot.TEAL),
                    ImportReadingBookmark.Page(1, Mushaf.INDOPAK_15_LINES, at(300), ReadingBookmarkSlot.ORANGE)
                )
            )
        )

        assertEquals(1, result.readingBookmarksImported)
        assertTrue(result.changed)
        val row = rows().single()
        assertEquals(Triple(1L, "page", 300L), Triple(row.slot, row.location_type, row.location_key))
    }

    @Test
    fun `empty merge leaves reading bookmarks unchanged and reports zero`() = runTest {
        insertSlot(1, "Keep")
        val before = rows()

        val result = repository.importData(PersistenceImportData())

        assertEquals(0, result.readingBookmarksImported)
        assertEquals(before, rows())
    }

    private fun insertSlot(slot: Long, name: String) {
        database.synced.readingBookmarksQueries.insert(slot, name, "page", 88, null, 5, null, 50, 100)
    }

    private fun rows() = database.synced.readingBookmarksQueries.all().executeAsList()
}

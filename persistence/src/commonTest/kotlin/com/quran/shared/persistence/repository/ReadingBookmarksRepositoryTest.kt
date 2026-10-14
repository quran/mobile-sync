package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.EmptyReadingBookmark
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ReadingBookmarksRepositoryTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var repository: ReadingBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        repository = ReadingBookmarksRepositoryImpl(database.synced)
    }

    @Test
    fun `three slots support only ayah and page locations`() = runTest {
        repository.setAyahReadingBookmark(slot = ReadingBookmarkSlot.TEAL, sura = 2, ayah = 255)
        repository.setPageReadingBookmark(slot = ReadingBookmarkSlot.ORANGE, page = 42)
        repository.renameReadingBookmark(slot = ReadingBookmarkSlot.RED, name = "Resume")

        val bookmarks = repository.getReadingBookmarks()
        val ayah = assertIs<AyahReadingBookmark>(bookmarks[0])
        val page = assertIs<PageReadingBookmark>(bookmarks[1])
        val empty = assertIs<EmptyReadingBookmark>(bookmarks[2])

        assertEquals(ReadingBookmarkSlot.TEAL, ayah.slot)
        assertEquals(2 to 255, ayah.sura to ayah.ayah)
        assertEquals(ReadingBookmarkSlot.ORANGE, page.slot)
        assertEquals(42, page.page)
        assertEquals(ReadingBookmarkSlot.RED, empty.slot)
        assertEquals("Resume", empty.name)
        assertEquals(listOf("ayah", "page", null), rows().map { it.location_type })
        assertEquals(emptyList(), database.synced.collectionsQueries.collectionAyahsAll().executeAsList())
    }

    @Test
    fun `a slot keeps one row and its id across writes`() = runTest {
        val first = repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 1, at(100))

        val second = repository.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 2, 255, at(200))

        assertEquals(first.id, second.id)
        assertEquals(1, rows().size)
        assertEquals(100L, rows().single().created_at)
    }

    @Test
    fun `clearing a slot keeps its row id and name`() = runTest {
        repository.setPageReadingBookmark(slot = ReadingBookmarkSlot.ORANGE, page = 88, timestamp = at(100))
        val named = repository.renameReadingBookmark(ReadingBookmarkSlot.ORANGE, "Study", at(150))

        val cleared = assertIs<EmptyReadingBookmark>(repository.clearReadingBookmark(ReadingBookmarkSlot.ORANGE, at(200)))

        val row = rows().single()
        assertEquals(named.id, cleared.id)
        assertEquals("Study", cleared.name)
        assertNull(row.location_type)
        assertNull(row.location_key)
        assertNull(row.location_verse)
        assertNull(row.mushaf_id)
    }

    @Test
    fun `a write keeps the slot's server metadata`() = runTest {
        database.synced.readingBookmarksQueries.insert(1, "Daily", "page", 88, null, 5, METADATA, 50, 100)

        repository.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 2, 255, at(200))
        repository.clearReadingBookmark(ReadingBookmarkSlot.TEAL, at(300))

        assertEquals(METADATA, rows().single().metadata)
    }

    @Test
    fun `explicit timestamps are stored for every reading bookmark mutation`() = runTest {
        repository.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 2, 255, at(100))
        assertEquals(100L, slotRow(1).updated_at)

        repository.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, 42, at(200))
        assertEquals(200L, slotRow(2).updated_at)

        repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Resume", at(300))
        assertEquals(300L, slotRow(1).updated_at)

        repository.clearReadingBookmark(ReadingBookmarkSlot.ORANGE, at(400))
        assertEquals(400L, slotRow(2).updated_at)
    }

    @Test
    fun `names are trimmed and an empty name is stored as null`() = runTest {
        val trimmed = repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "  Daily \n", at(100))
        val empty = repository.renameReadingBookmark(ReadingBookmarkSlot.ORANGE, " 　", at(100))

        assertEquals("Daily", trimmed.name)
        assertNull(empty.name)
        assertEquals(listOf("Daily", null), rows().map { it.name })
    }

    @Test
    fun `a page bookmark set here uses the Madani mushaf`() = runTest {
        val page = assertIs<PageReadingBookmark>(repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 42, at(100)))

        assertEquals(PageReadingBookmark.MADANI_MUSHAF_ID, page.mushafId)
        assertEquals(5L, slotRow(1).mushaf_id)
    }

    @Test
    fun `a page bookmark from another mushaf shows its mushaf`() = runTest {
        database.synced.readingBookmarksQueries.insert(2, null, "page", 300, null, 6, null, 50, 100)

        val page = assertIs<PageReadingBookmark>(repository.getReadingBookmarks().single())

        assertEquals(300, page.page)
        assertEquals(6, page.mushafId)
    }

    @Test
    fun `a surah location set on the web shows as an empty slot and is kept`() = runTest {
        database.synced.readingBookmarksQueries.insert(3, "Kahf", "surah", 18, null, 5, null, 50, 100)

        val empty = assertIs<EmptyReadingBookmark>(repository.getReadingBookmarks().single())

        assertEquals(ReadingBookmarkSlot.RED, empty.slot)
        assertEquals("Kahf", empty.name)
        assertEquals("surah", slotRow(3).location_type)
    }

    @Test
    fun `slots outside one through three are stored but not shown`() = runTest {
        database.synced.readingBookmarksQueries.insert(5, null, "page", 42, null, 5, null, 50, 100)
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 1, at(100))

        assertEquals(listOf(ReadingBookmarkSlot.TEAL), repository.getReadingBookmarks().map { it.slot })
        assertEquals(5L, slotRow(5).slot)
    }

    @Test
    fun `reading bookmarks flow emits the slots`() = runTest {
        repository.setAyahReadingBookmark(ReadingBookmarkSlot.ORANGE, 2, 255, at(100))

        val bookmark = assertIs<AyahReadingBookmark>(repository.getReadingBookmarksFlow().first().single())

        assertEquals(ReadingBookmarkSlot.ORANGE, bookmark.slot)
    }

    private fun rows() = database.synced.readingBookmarksQueries.all().executeAsList()

    private fun slotRow(slot: Long) = database.synced.readingBookmarksQueries.bySlot(slot).executeAsOne()

    private companion object {
        const val METADATA = """{"color":"teal"}"""
    }
}

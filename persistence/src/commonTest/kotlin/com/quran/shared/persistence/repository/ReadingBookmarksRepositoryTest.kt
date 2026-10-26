package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.EmptyReadingBookmark
import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        repository.setPageReadingBookmark(slot = ReadingBookmarkSlot.ORANGE, page = 42, mushaf = Mushaf.QCF_V2)
        repository.renameReadingBookmark(slot = ReadingBookmarkSlot.RED, name = "Resume", mushaf = Mushaf.QCF_V2)

        val bookmarks = repository.getReadingBookmarks(Mushaf.QCF_V2)
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
        val first = repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 1, Mushaf.QCF_V2, at(100))

        val second = repository.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 2, 255, at(200))

        assertEquals(first.id, second.id)
        assertEquals(1, rows().size)
        assertEquals(100L, rows().single().created_at)
    }

    @Test
    fun `clearing a slot keeps its row id and name`() = runTest {
        repository.setPageReadingBookmark(slot = ReadingBookmarkSlot.ORANGE, page = 88, mushaf = Mushaf.QCF_V2, timestamp = at(100))
        val named = repository.renameReadingBookmark(ReadingBookmarkSlot.ORANGE, "Study", Mushaf.QCF_V2, at(150))

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
        database.synced.readingBookmarksQueries.insert(1, "Daily", "page", 88, null, 5, "pages_604", METADATA, 50, 100)

        repository.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 2, 255, at(200))
        repository.clearReadingBookmark(ReadingBookmarkSlot.TEAL, at(300))

        assertEquals(METADATA, rows().single().metadata)
    }

    @Test
    fun `explicit timestamps are stored for every reading bookmark mutation`() = runTest {
        repository.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 2, 255, at(100))
        assertEquals(100L, slotRow(1).updated_at)

        repository.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, 42, Mushaf.QCF_V2, at(200))
        assertEquals(200L, slotRow(2).updated_at)

        repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Resume", Mushaf.QCF_V2, at(300))
        assertEquals(300L, slotRow(1).updated_at)

        repository.clearReadingBookmark(ReadingBookmarkSlot.ORANGE, at(400))
        assertEquals(400L, slotRow(2).updated_at)
    }

    @Test
    fun `names are trimmed and an empty name is stored as null`() = runTest {
        val trimmed = repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "  Daily \n", Mushaf.QCF_V2, at(100))
        val empty = repository.renameReadingBookmark(ReadingBookmarkSlot.ORANGE, " 　", Mushaf.QCF_V2, at(100))

        assertEquals("Daily", trimmed.name)
        assertNull(empty.name)
        assertEquals(listOf("Daily", null), rows().map { it.name })
    }

    @Test
    fun `a 15-line IndoPak page is stored as the server numbers it`() = runTest {
        val page = assertIs<PageReadingBookmark>(
            repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 537, Mushaf.INDOPAK_15_LINES, at(100))
        )

        assertEquals(537 to Mushaf.INDOPAK_15_LINES, page.page to page.mushaf)
        assertEquals(Triple(536L, 6L, "pages_610"), slotRow(1).let { Triple(it.location_key, it.mushaf_id, it.location_group) })
    }

    @Test
    fun `a page reads in another mushaf as the page with the same verses`() = runTest {
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 537, Mushaf.INDOPAK_15_LINES, at(100))
        database.synced.readingBookmarksQueries.insert(2, null, "page", 334, null, 7, "pages_548", null, 50, 100)

        val madani = repository.getReadingBookmarks(Mushaf.QCF_V2).map { assertIs<PageReadingBookmark>(it) }
        val indoPak = repository.getReadingBookmarks(Mushaf.INDOPAK_15_LINES).map { assertIs<PageReadingBookmark>(it) }

        assertEquals(listOf(536, 371), madani.map { it.page })
        assertEquals(listOf(537, 372), indoPak.map { it.page })
    }

    @Test
    fun `a page of the mushaf's own group keeps its number`() = runTest {
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 584, Mushaf.QCF_V1, at(100))
        database.synced.readingBookmarksQueries.insert(2, null, "page", 300, null, 6, null, null, 50, 100)

        val madani = assertIs<PageReadingBookmark>(repository.getReadingBookmarksFlow(Mushaf.QCF_V2).first()[0])
        val indoPak = assertIs<PageReadingBookmark>(repository.getReadingBookmarks(Mushaf.INDOPAK_15_LINES)[1])

        assertEquals(584, madani.page)
        assertEquals(301, indoPak.page, "a row with only a mushaf is read in the group it picks")
    }

    @Test
    fun `a page outside the mushaf is rejected and nothing is written`() = runTest {
        listOf(Mushaf.QCF_V1 to 605, Mushaf.QCF_V2 to 0, Mushaf.INDOPAK_15_LINES to 1, Mushaf.INDOPAK_15_LINES to 612).forEach { (mushaf, page) ->
            assertFailsWith<IllegalArgumentException>("$mushaf $page") {
                repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, page, mushaf, at(100))
            }
        }
        assertEquals(emptyList(), rows())

        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 2, Mushaf.INDOPAK_15_LINES, at(100))
        repository.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, 611, Mushaf.INDOPAK_15_LINES, at(100))
        assertEquals(listOf(1L, 610L), rows().map { it.location_key })
    }

    @Test
    fun `a page of an unknown group is an empty slot and a rename keeps it`() = runTest {
        database.synced.readingBookmarksQueries.insert(1, null, "page", 700, null, 5, "pages_720", null, 50, 100)

        assertIs<EmptyReadingBookmark>(repository.getReadingBookmarks(Mushaf.QCF_V2).single())
        val renamed = assertIs<EmptyReadingBookmark>(repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Khatm", Mushaf.QCF_V2, at(200)))

        assertEquals("Khatm", renamed.name)
        val row = slotRow(1)
        assertEquals(listOf<Any?>("page", 700L, 5L, "pages_720"), listOf(row.location_type, row.location_key, row.mushaf_id, row.location_group))
    }

    @Test
    fun `a stored page outside its group is an empty slot`() = runTest {
        database.synced.readingBookmarksQueries.insert(1, null, "page", 605, null, 5, "pages_604", null, 50, 100)
        database.synced.readingBookmarksQueries.insert(2, null, "page", 549, null, 7, "pages_548", null, 50, 100)

        val slots = repository.getReadingBookmarks(Mushaf.QCF_V2)

        assertEquals(2, slots.size)
        slots.forEach { assertIs<EmptyReadingBookmark>(it, "${it.slot}: not clamped onto a real page") }
    }

    @Test
    fun `a rename returns the slot's page mapped into the mushaf`() = runTest {
        database.synced.readingBookmarksQueries.insert(1, null, "page", 334, null, 7, "pages_548", null, 50, 100)

        val renamed = assertIs<PageReadingBookmark>(repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Khatm", Mushaf.QCF_V2, at(200)))

        assertEquals(Triple(371, Mushaf.QCF_V2, "Khatm"), Triple(renamed.page, renamed.mushaf, renamed.name))
        assertEquals(334L to "pages_548", slotRow(1).let { it.location_key to it.location_group }, "the stored page is unchanged")
    }

    @Test
    fun `a surah location set on the web shows as an empty slot and is kept`() = runTest {
        database.synced.readingBookmarksQueries.insert(3, "Kahf", "surah", 18, null, 5, null, null, 50, 100)

        val empty = assertIs<EmptyReadingBookmark>(repository.getReadingBookmarks(Mushaf.QCF_V2).single())

        assertEquals(ReadingBookmarkSlot.RED, empty.slot)
        assertEquals("Kahf", empty.name)
        assertEquals("surah", slotRow(3).location_type)
    }

    @Test
    fun `slots outside one through three are stored but not shown`() = runTest {
        database.synced.readingBookmarksQueries.insert(5, null, "page", 42, null, 5, "pages_604", null, 50, 100)
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 1, Mushaf.QCF_V2, at(100))

        assertEquals(listOf(ReadingBookmarkSlot.TEAL), repository.getReadingBookmarks(Mushaf.QCF_V2).map { it.slot })
        assertEquals(5L, slotRow(5).slot)
    }

    @Test
    fun `reading bookmarks flow emits the slots`() = runTest {
        repository.setAyahReadingBookmark(ReadingBookmarkSlot.ORANGE, 2, 255, at(100))

        val bookmark = assertIs<AyahReadingBookmark>(repository.getReadingBookmarksFlow(Mushaf.QCF_V2).first().single())

        assertEquals(ReadingBookmarkSlot.ORANGE, bookmark.slot)
    }

    private fun rows() = database.synced.readingBookmarksQueries.all().executeAsList()

    private fun slotRow(slot: Long) = database.synced.readingBookmarksQueries.bySlot(slot).executeAsOne()

    private companion object {
        const val METADATA = """{"color":"teal"}"""
    }
}

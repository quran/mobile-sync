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
        repository.setPageReadingBookmark(slot = ReadingBookmarkSlot.ORANGE, page = 42, mushaf = Mushaf.MADANI_1440)
        repository.renameReadingBookmark(slot = ReadingBookmarkSlot.RED, name = "Resume", mushaf = Mushaf.MADANI_1440)

        val bookmarks = repository.getReadingBookmarks(Mushaf.MADANI_1440)
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
        val first = repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 1, Mushaf.MADANI_1440, at(100))

        val second = repository.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 2, 255, at(200))

        assertEquals(first.id, second.id)
        assertEquals(1, rows().size)
        assertEquals(100L, rows().single().created_at)
    }

    @Test
    fun `clearing a slot keeps its row id and name`() = runTest {
        repository.setPageReadingBookmark(slot = ReadingBookmarkSlot.ORANGE, page = 88, mushaf = Mushaf.MADANI_1440, timestamp = at(100))
        val named = repository.renameReadingBookmark(ReadingBookmarkSlot.ORANGE, "Study", Mushaf.MADANI_1440, at(150))

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

        repository.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, 42, Mushaf.MADANI_1440, at(200))
        assertEquals(200L, slotRow(2).updated_at)

        repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Resume", Mushaf.MADANI_1440, at(300))
        assertEquals(300L, slotRow(1).updated_at)

        repository.clearReadingBookmark(ReadingBookmarkSlot.ORANGE, at(400))
        assertEquals(400L, slotRow(2).updated_at)
    }

    @Test
    fun `names are trimmed and an empty name is stored as null`() = runTest {
        val trimmed = repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "  Daily \n", Mushaf.MADANI_1440, at(100))
        val empty = repository.renameReadingBookmark(ReadingBookmarkSlot.ORANGE, " 　", Mushaf.MADANI_1440, at(100))

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
        assertEquals(536L to 6L, slotRow(1).let { it.location_key to it.mushaf_id })
    }

    @Test
    fun `a page reads in another mushaf as the page with the same verses`() = runTest {
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 537, Mushaf.INDOPAK_15_LINES, at(100))
        database.synced.readingBookmarksQueries.insert(2, null, "page", 334, null, 7, null, 50, 100)

        val madani = repository.getReadingBookmarks(Mushaf.MADANI_1440).map { assertIs<PageReadingBookmark>(it) }
        val indoPak = repository.getReadingBookmarks(Mushaf.INDOPAK_15_LINES).map { assertIs<PageReadingBookmark>(it) }

        assertEquals(listOf(536, 371), madani.map { it.page })
        assertEquals(listOf(537, 372), indoPak.map { it.page })
    }

    /** Before any sync the slot carries the mushaf it was set in, so the two Madani layouts map by their tables. */
    @Test
    fun `a page set in one Madani layout maps into the other and back`() = runTest {
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 121, Mushaf.MADANI_1405, at(100))

        val madani1440 = assertIs<PageReadingBookmark>(repository.getReadingBookmarks(Mushaf.MADANI_1440).single())
        repository.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, madani1440.page, Mushaf.MADANI_1440, at(200))
        val back = assertIs<PageReadingBookmark>(repository.getReadingBookmarks(Mushaf.MADANI_1405)[1])

        assertEquals(121, madani1440.page)
        assertEquals(121, back.page)
        assertEquals(listOf(2L, 1L), rows().map { it.mushaf_id }, "each slot keeps the mushaf it was set in")
    }

    /**
     * Today's backend echoes 5 (KFGQPC Hafs) for every `pages_604` page; it is read in the 1440 layout. Page 585 tells
     * the layouts apart: the 15-line IndoPak page with its verses is 589 from the 1440 layout and 590 from the 1405 one.
     */
    @Test
    fun `the server's stand-in is read in the 1440 layout`() = runTest {
        database.synced.readingBookmarksQueries.insert(1, null, "page", 585, null, 5, null, 50, 100)
        database.synced.readingBookmarksQueries.insert(2, null, "page", 585, null, 2, null, 50, 100)

        val madani1405 = repository.getReadingBookmarks(Mushaf.MADANI_1405).map { assertIs<PageReadingBookmark>(it) }
        val indoPak = repository.getReadingBookmarks(Mushaf.INDOPAK_15_LINES).map { assertIs<PageReadingBookmark>(it) }

        assertEquals(listOf(585, 585), madani1405.map { it.page })
        assertEquals(listOf(590, 591), indoPak.map { it.page }, "app pages: server pages 589 and 590")
    }

    /** The server has no Shemerly mushaf yet, so a Shemerly page is stored and sent as the 1440 page with its verses. */
    @Test
    fun `a Shemerly page is stored as the 1440 page with its verses and read in each mushaf`() = runTest {
        val set = assertIs<PageReadingBookmark>(repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 250, Mushaf.SHEMERLY, at(100)))
        repository.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, 300, Mushaf.MADANI_1440, at(100))

        assertEquals(250 to Mushaf.SHEMERLY, set.page to set.mushaf)
        assertEquals(listOf(302L to 1L, 300L to 1L), rows().map { it.location_key to it.mushaf_id })
        assertEquals(listOf(250, 248), pages(Mushaf.SHEMERLY))
        assertEquals(listOf(302, 300), pages(Mushaf.MADANI_1440))
        assertEquals(listOf(303, 301), pages(Mushaf.INDOPAK_15_LINES))
    }

    /** One of the seven Shemerly pages that don't round-trip through the 1440 layout (ReadingBookmarkPageMapperTest). */
    @Test
    fun `a Shemerly page without its own 1440 page reads back as its neighbour`() = runTest {
        val set = assertIs<PageReadingBookmark>(repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 9, Mushaf.SHEMERLY, at(100)))

        assertEquals(10, set.page)
        assertEquals(10L to 1L, slotRow(1).let { it.location_key to it.mushaf_id })
    }

    @Test
    fun `a riwayah page is stored unchanged and read only in its own mushaf`() = runTest {
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 300, Mushaf.WARSH, at(100))
        repository.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, 604, Mushaf.QALOUN, at(100))

        assertEquals(listOf(300L to 20L, 604L to 22L), rows().map { it.location_key to it.mushaf_id })
        val warsh = repository.getReadingBookmarks(Mushaf.WARSH)
        val qaloun = repository.getReadingBookmarks(Mushaf.QALOUN)
        assertEquals(300 to Mushaf.WARSH, assertIs<PageReadingBookmark>(warsh[0]).let { it.page to it.mushaf })
        assertIs<EmptyReadingBookmark>(warsh[1])
        assertIs<EmptyReadingBookmark>(qaloun[0])
        assertEquals(604 to Mushaf.QALOUN, assertIs<PageReadingBookmark>(qaloun[1]).let { it.page to it.mushaf })
    }

    @Test
    fun `a riwayah page is an empty slot in every Hafs mushaf`() = runTest {
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 300, Mushaf.WARSH, at(100))
        repository.setPageReadingBookmark(ReadingBookmarkSlot.ORANGE, 604, Mushaf.QALOUN, at(100))

        listOf(Mushaf.MADANI_1405, Mushaf.MADANI_1440, Mushaf.INDOPAK_15_LINES, Mushaf.SHEMERLY).forEach { mushaf ->
            val slots = repository.getReadingBookmarks(mushaf)
            assertEquals(2, slots.size, "$mushaf")
            slots.forEach { assertIs<EmptyReadingBookmark>(it, "$mushaf ${it.slot}") }
        }
    }

    @Test
    fun `a Hafs page is an empty slot in a riwayah`() = runTest {
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 300, Mushaf.MADANI_1440, at(100))
        database.synced.readingBookmarksQueries.insert(2, null, "page", 300, null, 5, null, 50, 100)

        listOf(Mushaf.WARSH, Mushaf.QALOUN).forEach { mushaf ->
            val slots = repository.getReadingBookmarks(mushaf)
            assertEquals(2, slots.size)
            slots.forEach { assertIs<EmptyReadingBookmark>(it, "$mushaf ${it.slot}") }
        }
    }

    @Test
    fun `a rename returns a riwayah page in its own mushaf and keeps it in another`() = runTest {
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 300, Mushaf.WARSH, at(100))

        val inWarsh = assertIs<PageReadingBookmark>(repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Khatm", Mushaf.WARSH, at(200)))
        val inMadani = assertIs<EmptyReadingBookmark>(repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Hifz", Mushaf.MADANI_1440, at(300)))

        assertEquals(Triple(300, Mushaf.WARSH, "Khatm"), Triple(inWarsh.page, inWarsh.mushaf, inWarsh.name))
        assertEquals("Hifz", inMadani.name)
        assertEquals(300L to 20L, slotRow(1).let { it.location_key to it.mushaf_id }, "the stored page is unchanged")
    }

    @Test
    fun `a rename returns a 1440 page as its Shemerly page`() = runTest {
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 302, Mushaf.MADANI_1440, at(100))

        val renamed = assertIs<PageReadingBookmark>(repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Khatm", Mushaf.SHEMERLY, at(200)))

        assertEquals(Triple(250, Mushaf.SHEMERLY, "Khatm"), Triple(renamed.page, renamed.mushaf, renamed.name))
        assertEquals(302L to 1L, slotRow(1).let { it.location_key to it.mushaf_id })
    }

    @Test
    fun `a page outside the mushaf is rejected and nothing is written`() = runTest {
        listOf(
            Mushaf.MADANI_1405 to 605, Mushaf.MADANI_1440 to 0, Mushaf.INDOPAK_15_LINES to 1, Mushaf.INDOPAK_15_LINES to 612,
            Mushaf.SHEMERLY to 0, Mushaf.SHEMERLY to 522, Mushaf.WARSH to 0, Mushaf.WARSH to 605, Mushaf.QALOUN to 605
        ).forEach { (mushaf, page) ->
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
    fun `the first and last pages of the new mushafs are accepted`() = runTest {
        listOf(Mushaf.SHEMERLY to listOf(1, 521), Mushaf.WARSH to listOf(1, 604), Mushaf.QALOUN to listOf(1, 604)).forEach { (mushaf, pages) ->
            pages.forEach { page ->
                val set = assertIs<PageReadingBookmark>(repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, page, mushaf, at(100)))
                assertEquals(page, set.page, "$mushaf $page")
            }
        }
    }

    /** Warsh (20) numbers verses differently, so its pages don't map; 99 is a mushaf this library doesn't know. */
    @Test
    fun `a page of a mushaf without a layout here is an empty slot and a rename keeps it`() = runTest {
        database.synced.readingBookmarksQueries.insert(1, null, "page", 300, null, 20, null, 50, 100)
        database.synced.readingBookmarksQueries.insert(2, null, "page", 300, null, 99, null, 50, 100)

        repository.getReadingBookmarks(Mushaf.MADANI_1440).forEach { assertIs<EmptyReadingBookmark>(it, "${it.slot}") }
        val renamed = assertIs<EmptyReadingBookmark>(repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Khatm", Mushaf.MADANI_1440, at(200)))

        assertEquals("Khatm", renamed.name)
        assertEquals(listOf(Triple(300L, 20L, "page"), Triple(300L, 99L, "page")), rows().map { Triple(it.location_key, it.mushaf_id, it.location_type) })
    }

    @Test
    fun `a stored page outside its mushaf is an empty slot`() = runTest {
        database.synced.readingBookmarksQueries.insert(1, null, "page", 605, null, 5, null, 50, 100)
        database.synced.readingBookmarksQueries.insert(2, null, "page", 549, null, 7, null, 50, 100)

        val slots = repository.getReadingBookmarks(Mushaf.MADANI_1440)

        assertEquals(2, slots.size)
        slots.forEach { assertIs<EmptyReadingBookmark>(it, "${it.slot}: not clamped onto a real page") }
    }

    @Test
    fun `a rename returns the slot's page mapped into the mushaf`() = runTest {
        database.synced.readingBookmarksQueries.insert(1, null, "page", 334, null, 7, null, 50, 100)

        val renamed = assertIs<PageReadingBookmark>(repository.renameReadingBookmark(ReadingBookmarkSlot.TEAL, "Khatm", Mushaf.MADANI_1440, at(200)))

        assertEquals(Triple(371, Mushaf.MADANI_1440, "Khatm"), Triple(renamed.page, renamed.mushaf, renamed.name))
        assertEquals(334L to 7L, slotRow(1).let { it.location_key to it.mushaf_id }, "the stored page is unchanged")
    }

    @Test
    fun `a surah location set on the web shows as an empty slot and is kept`() = runTest {
        database.synced.readingBookmarksQueries.insert(3, "Kahf", "surah", 18, null, 5, null, 50, 100)

        val empty = assertIs<EmptyReadingBookmark>(repository.getReadingBookmarks(Mushaf.MADANI_1440).single())

        assertEquals(ReadingBookmarkSlot.RED, empty.slot)
        assertEquals("Kahf", empty.name)
        assertEquals("surah", slotRow(3).location_type)
    }

    @Test
    fun `slots outside one through three are stored but not shown`() = runTest {
        database.synced.readingBookmarksQueries.insert(5, null, "page", 42, null, 5, null, 50, 100)
        repository.setPageReadingBookmark(ReadingBookmarkSlot.TEAL, 1, Mushaf.MADANI_1440, at(100))

        assertEquals(listOf(ReadingBookmarkSlot.TEAL), repository.getReadingBookmarks(Mushaf.MADANI_1440).map { it.slot })
        assertEquals(5L, slotRow(5).slot)
    }

    @Test
    fun `reading bookmarks flow emits the slots`() = runTest {
        repository.setAyahReadingBookmark(ReadingBookmarkSlot.ORANGE, 2, 255, at(100))

        val bookmark = assertIs<AyahReadingBookmark>(repository.getReadingBookmarksFlow(Mushaf.MADANI_1440).first().single())

        assertEquals(ReadingBookmarkSlot.ORANGE, bookmark.slot)
    }

    private fun rows() = database.synced.readingBookmarksQueries.all().executeAsList()

    private suspend fun pages(mushaf: Mushaf) = repository.getReadingBookmarks(mushaf).map { assertIs<PageReadingBookmark>(it).page }

    private fun slotRow(slot: Long) = database.synced.readingBookmarksQueries.bySlot(slot).executeAsOne()

    private companion object {
        const val METADATA = """{"color":"teal"}"""
    }
}

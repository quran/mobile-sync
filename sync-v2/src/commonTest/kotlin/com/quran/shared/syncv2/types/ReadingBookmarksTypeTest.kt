package com.quran.shared.syncv2.types

import com.quran.shared.syncv2.Change
import com.quran.shared.syncv2.MergeItem
import com.quran.shared.syncv2.Merger
import com.quran.shared.syncv2.Refs
import com.quran.shared.syncv2.RemoteEntry
import com.quran.shared.syncv2.RemoteItem
import com.quran.shared.syncv2.SyncType
import com.quran.shared.syncv2.inMemoryDatabase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reading bookmarks type over each platform's SQLite driver, without a server. */
class ReadingBookmarksTypeTest {
    private val database = inMemoryDatabase()
    private val type = ReadingBookmarksType(database)
    private val repository = ReadingBookmarksRepository(database)
    private val refs = object : Refs {
        override fun remoteId(type: SyncType, localId: Long): String? = null
        override fun localId(type: SyncType, remoteId: String): Long? = null
    }

    @Test
    fun a_cleared_slot_keeps_its_name_and_names_are_trimmed() {
        repository.set(1, ReadingLocation.ayah(18, 10), 1)
        repository.rename(1, "  Khatm ", 2)
        repository.clear(1, 3)
        repository.rename(2, "   ", 4)

        val (first, second) = repository.readingBookmarks()

        assertEquals(Triple(1, "Khatm", null), Triple(first.slot, first.name, first.location))
        assertEquals(1L to 3L, first.createdAt to first.updatedAt)
        assertNull(second.name)
    }

    /** The server keeps only the group; a location carries the group and its stand-in mushaf. */
    @Test
    fun a_location_carries_its_group_and_the_group_stand_in_mushaf() {
        assertEquals(5, ReadingLocation.ayah(2, 255, mushafId = 1).mushafId)
        assertEquals(5, ReadingLocation.ayah(2, 255, mushafId = 7).mushafId)
        assertNull(ReadingLocation.ayah(2, 255, mushafId = 7).group)
        assertEquals(5 to "pages_604", ReadingLocation.page(300, mushafId = 2).let { it.mushafId to it.group })
        assertEquals(7 to "pages_548", ReadingLocation.page(300, mushafId = 7).let { it.mushafId to it.group })
        assertEquals(6 to "pages_610", ReadingLocation.page(300, mushafId = 6).let { it.mushafId to it.group })
        assertEquals(5, ReadingLocation.of("juz", 30, mushafId = 7).mushafId)
    }

    /** A page is read by its `group`, and by its `mushafId` only when the server sends no group. */
    @Test
    fun a_server_page_is_read_by_its_group() {
        val pages = listOf(
            page("rb1", key = 300, mushafId = 5, group = "pages_548"),
            page("rb4", key = 300, mushafId = 7, group = null)
        )

        val locations = type.fromRemote(pages, refs).map { ReadingBookmarksType.toBookmark(0, assertIs<RemoteItem.Upsert>(it).state).location }

        assertEquals(
            listOf(
                ReadingLocation.page(300, PageGroup.PAGES_548),
                ReadingLocation.page(300, PageGroup.PAGES_548)
            ),
            locations
        )
    }

    /** A group this library doesn't know is never read as 604 pages: it's kept, and written back with its mushaf. */
    @Test
    fun an_unknown_page_group_is_kept_as_the_server_sent_it() {
        val item = assertIs<RemoteItem.Upsert>(type.fromRemote(listOf(page("rb1", key = 700, mushafId = 9, group = "pages_720")), refs).single())
        val local = type.insert(item.state)

        val location = ReadingBookmarksType.toBookmark(local, type.readAll().getValue(local)).location!!
        repository.rename(1, "Khatm", 2)
        val renamed = repository.readingBookmarks().single()
        val sent = type.mutation(Change.Update("rb1", item.state, type.readAll().getValue(local)), refs).data!!

        assertEquals(Triple("pages_720", 9, null), Triple(location.group, location.mushafId, location.pageGroup))
        assertEquals(location, renamed.location)
        assertEquals(JsonPrimitive(9), sent["mushafId"])
        assertEquals(JsonPrimitive(700), sent["key"])
        assertNull(type.validate(Change.Create(item.state)), "an unknown group's page count is unknown")
    }

    @Test
    fun a_page_must_fit_in_its_group() {
        fun validate(location: ReadingLocation) = type.validate(Change.Create(ReadingBookmarksType.bookmarkState(1, null, location, null, 1, 1)))

        assertNull(validate(ReadingLocation.page(548, PageGroup.PAGES_548)))
        assertEquals("INVALID_PAGE", validate(ReadingLocation.page(549, PageGroup.PAGES_548)))
        assertEquals("INVALID_PAGE", validate(ReadingLocation.page(0, PageGroup.PAGES_604)))
    }

    @Test
    fun parked_slots_are_one_per_id() {
        repository.set(1, ReadingLocation.page(5), 1)
        // A row restored under -1 next to row 1: their parked keys must differ too.
        type.insert(ReadingBookmarksType.bookmarkState(2, null, null, null, 1, 1), localId = -1)

        type.readAll().keys.forEach(type::park)

        val slots = repository.readingBookmarks().map { it.slot }
        assertTrue(slots.all { it < 0 }, "$slots")
        assertEquals(2, slots.toSet().size)
    }

    @Test
    fun slots_map_from_the_server() {
        val placed = buildJsonObject {
            put("slot", 2); put("name", JsonNull); put("type", "page"); put("key", 300); put("verseNumber", JsonNull)
            put("mushafId", 7); put("metadata", buildJsonObject { put("source", "QDC_WEB"); put("b", 2); put("a", 1) })
        }
        val cleared = buildJsonObject {
            put("slot", 3); put("name", "Kursi"); listOf("type", "key", "verseNumber", "mushafId", "metadata").forEach { put(it, JsonNull) }
        }
        val entries = listOf(entry("rb1", JsonObject(emptyMap())), entry("rb2", placed), entry("rb3", cleared))

        val items = type.fromRemote(entries, refs).map { assertIs<RemoteItem.Upsert>(it) }

        assertEquals(listOf("rb2", "rb3"), items.map { it.remoteId }, "{} is skipped, never read as a clear")
        val metadata = buildJsonObject { put("a", 1); put("b", 2) }
        assertEquals(ReadingBookmarksType.bookmarkState(2, null, ReadingLocation.page(300, 7), metadata, 1, 1), items[0].state)
        assertEquals(ReadingBookmarksType.bookmarkState(3, "Kursi", null, null, 1, 1), items[1].state)
    }

    @Test
    fun a_clear_is_an_update_with_null_locations_and_the_metadata_kept() {
        val metadata = buildJsonObject { put("color", "teal") }
        val cleared = ReadingBookmarksType.bookmarkState(1, "Khatm", null, metadata, 1, 2)
        val placed = ReadingBookmarksType.bookmarkState(1, null, ReadingLocation.ayah(2, 255), metadata, 1, 2)

        val clear = type.mutation(Change.Update("rb1", placed, cleared), refs).data!!
        val set = type.mutation(Change.Create(placed), refs).data!!

        listOf("mushafId", "type", "group", "key", "verseNumber").forEach { assertEquals(JsonNull, clear[it], it) }
        assertEquals(JsonPrimitive("Khatm"), clear["name"])
        assertEquals(metadata, clear["metadata"])
        assertEquals(JsonPrimitive(5), set["mushafId"])
        assertTrue("mushaf" !in set && "group" !in set)
    }

    /**
     * Name and location merge separately, and the location as one value: a rename on one side and a newer move on
     * the other both survive, and two moves never mix their sura and ayah.
     */
    @Test
    fun name_and_location_merge_separately_and_the_location_whole() {
        val base = ReadingBookmarksType.bookmarkState(1, null, ReadingLocation.ayah(18, 10), null, 0, 0)
        val local = ReadingBookmarksType.bookmarkState(1, null, ReadingLocation.ayah(18, 20), null, 0, 2)
        val renamed = ReadingBookmarksType.bookmarkState(1, "Khatm", ReadingLocation.ayah(18, 10), null, 0, 3)
        val moved = ReadingBookmarksType.bookmarkState(1, null, ReadingLocation.ayah(36, 10), null, 0, 1)

        val merger = Merger(type)

        val bothKept = merger.fieldMerge(base, local, renamed)
        assertEquals("Khatm", ReadingBookmarksType.toBookmark(0, bothKept).name)
        assertEquals(ReadingLocation.ayah(18, 20), ReadingBookmarksType.toBookmark(0, bothKept).location)
        assertEquals(ReadingLocation.ayah(18, 20), ReadingBookmarksType.toBookmark(0, merger.fieldMerge(base, local, moved)).location)
        val item = MergeItem(1, "rb1", base, local, RemoteItem.Upsert("rb1", renamed, 1), local)
        merger.merge(item)
        assertEquals("Khatm", ReadingBookmarksType.toBookmark(1, item.working!!).name)
    }

    private fun entry(id: String, data: JsonObject) = RemoteEntry("READING_BOOKMARK", id, "UPDATE", data, 1L)

    private fun page(id: String, key: Int, mushafId: Int, group: String?) = entry(id, buildJsonObject {
        put("slot", 1); put("name", JsonNull); put("type", "page"); put("key", key); put("verseNumber", JsonNull)
        put("mushafId", mushafId); group?.let { put("group", it) }
    })
}

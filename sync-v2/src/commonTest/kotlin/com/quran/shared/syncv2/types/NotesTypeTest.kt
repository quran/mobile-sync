package com.quran.shared.syncv2.types

import com.quran.shared.syncv2.Change
import com.quran.shared.syncv2.Refs
import com.quran.shared.syncv2.RemoteEntry
import com.quran.shared.syncv2.RemoteItem
import com.quran.shared.syncv2.SyncType
import com.quran.shared.syncv2.inMemoryDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The notes type over each platform's SQLite driver, without a server. */
class NotesTypeTest {
    private val database = inMemoryDatabase()
    private val type = NotesType(database)
    private val notes = NotesRepository(database)
    private val refs = object : Refs {
        override fun remoteId(type: SyncType, localId: Long): String? = null
        override fun localId(type: SyncType, remoteId: String): Long? = null
    }

    @Test
    fun notes_keep_every_range_in_order_and_accept_negative_ids() {
        val ranges = listOf(NoteRange(3, 18, 3, 18), NoteRange(2, 255, 2, 256))
        val id = notes.addNote("  Two passages  ", ranges, 10)
        type.insert(type.readAll().getValue(id), localId = -3)

        val all = notes.notes()

        assertEquals(listOf(-3L, id), all.map { it.localId }.sorted())
        all.forEach { assertEquals(ranges, it.ranges) }
        assertEquals("Two passages", all.first().body)
        type.delete(-3)
        assertEquals(listOf(id), type.readAll().keys.toList())
    }

    @Test
    fun an_edit_changes_the_body_only() {
        val id = notes.addNote("Written first", listOf(NoteRange(1, 1, 1, 1)), 100)

        notes.updateNote(id, "Edited", 200)

        val note = notes.notes().single()
        assertEquals("Edited" to 200L, note.body to note.updatedAt)
        assertEquals(100L to listOf(NoteRange(1, 1, 1, 1)), note.createdAt to note.ranges)
    }

    /** `clientCreatedAt` is the first free millisecond at or after the given time, found in one query. */
    @Test
    fun creates_take_the_first_free_millisecond() {
        val range = listOf(NoteRange(1, 1, 1, 1))
        listOf(100L, 100L, 102L, 100L, 50L, 100L).forEach { notes.addNote("A note body", range, it) }

        assertEquals(listOf(100L, 101L, 102L, 103L, 50L, 104L), notes.notes().map { it.createdAt })
    }

    @Test
    fun notes_map_from_the_server_with_their_archived_state() {
        val entries = listOf(
            entry("n1", buildJsonObject {
                put("body", "a &lt;b&gt; &amp; c")
                put("ranges", JsonArray(listOf(JsonPrimitive("2:255-2:256"), JsonPrimitive("3:18-3:18"))))
                put("clientCreatedAt", "2026-01-01T00:00:00Z")
                put("archivedAt", JsonNull)
            }),
            entry("n2", buildJsonObject {
                put("body", "archived")
                put("ranges", JsonArray(listOf(JsonPrimitive("1:1-1:1"))))
                put("archivedAt", "2026-01-02T00:00:00Z")
            }),
            entry("n3", JsonObject(emptyMap()))
        )

        val items = type.fromRemote(entries, refs).map { NotesType.toNote(0, assertIs<RemoteItem.Upsert>(it).state) }

        assertEquals(2, items.size, "{} is skipped")
        assertEquals(listOf(NoteRange(2, 255, 2, 256), NoteRange(3, 18, 3, 18)), items[0].ranges)
        assertEquals("a <b> & c", items[0].body)
        assertNull(items[0].archivedAt)
        assertEquals(1_767_312_000_000L, items[1].archivedAt, "an archived note is a note, not a delete")
    }

    @Test
    fun an_update_sends_the_escaped_body_and_its_time_only() {
        val state = NotesType.noteState("x < y", listOf(NoteRange(1, 1, 1, 7)), 1, 2, archivedAt = 3)

        val update = type.mutation(Change.Update("n1", state, state), refs).data!!
        val create = type.mutation(Change.Create(state), refs).data!!

        assertEquals(setOf("body", "clientUpdatedAt"), update.keys)
        assertEquals(JsonPrimitive("x &lt; y"), update["body"])
        assertEquals(JsonArray(listOf(JsonPrimitive("1:1-1:7"))), create["ranges"])
        assertEquals("INVALID_RANGE", type.validate(Change.Create(NotesType.noteState("Long enough", listOf(NoteRange(1, 8, 1, 8)), 1, 1))))
        assertEquals("BODY_LENGTH", type.validate(Change.Update("n1", state, NotesType.noteState("short", emptyList(), 1, 1))))
    }

    /** Equal new notes link by the trimmed body and the set of ranges; the creation time only ranks candidates. */
    @Test
    fun the_link_key_is_the_body_and_the_set_of_ranges() {
        val kursi = NoteRange(2, 255, 2, 255)
        val imran = NoteRange(3, 18, 3, 18)
        val key = type.linkKey(NotesType.noteState("Tom & Jerry", listOf(kursi, imran), 1_000, 1_000))

        val sameNote = type.linkKey(NotesType.noteState(" Tom & Jerry\n", listOf(imran, kursi), 7_000, 9_000))

        assertEquals(key.identity, sameNote.identity)
        // The legacy importer's rule too (persistence's ImportMergeTest checks the two agree).
        assertEquals(noteIdentity("Tom & Jerry", listOf(kursi, imran)), key.identity)
        assertEquals(1_000L to 7_000L, key.time to sameNote.time)
        assertNotEquals(key.identity, type.linkKey(NotesType.noteState("Tom & Jerry!", listOf(kursi, imran), 1_000, 1_000)).identity)
        assertNotEquals(key.identity, type.linkKey(NotesType.noteState("Tom & Jerry", listOf(kursi), 1_000, 1_000)).identity)
        // The outbox key still includes the exact creation time (EqualNewNotesTest covers lost-create resolution).
        val pushed = NotesType.noteState("Tom & Jerry", listOf(kursi), 1_000, 1_000)
        assertNotEquals(type.outboxKey(pushed), type.outboxKey(NotesType.noteState("Tom & Jerry", listOf(kursi), 1_001, 1_001)))
    }

    private fun entry(id: String, data: JsonObject) = RemoteEntry("NOTE", id, "UPDATE", data, 1L)
}

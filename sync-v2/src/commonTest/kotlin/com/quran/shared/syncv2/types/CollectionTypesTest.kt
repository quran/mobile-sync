package com.quran.shared.syncv2.types

import com.quran.shared.syncv2.Change
import com.quran.shared.syncv2.Field
import com.quran.shared.syncv2.MergeItem
import com.quran.shared.syncv2.Merger
import com.quran.shared.syncv2.Refs
import com.quran.shared.syncv2.RemoteItem
import com.quran.shared.syncv2.State
import com.quran.shared.syncv2.SyncType
import com.quran.shared.syncv2.get
import com.quran.shared.syncv2.inMemoryDatabase
import com.quran.shared.syncv2.stateOf
import com.quran.shared.syncv2.with
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The session, collection and verse types over each platform's SQLite driver, without a server. */
class CollectionTypesTest {
    private val database = inMemoryDatabase()
    private val collections = CollectionsRepository(database)
    private val collectionsType = CollectionsType(database)
    private val verses = CollectionAyahsType(database, collectionsType)
    private val refs = object : Refs {
        override fun remoteId(type: SyncType, localId: Long): String? = "col$localId"
        override fun localId(type: SyncType, remoteId: String): Long? = null
    }
    private val addedAt = Field.long("addedAt")

    @Test
    fun a_membership_sends_both_mushaf_keys_and_when_it_was_added() {
        val study = collections.addCollection("Study", 1).localId
        collections.addAyah(study, 2, 255, 1_700_000_000_000)

        val data = verses.mutation(Change.Create(verses.readAll().values.single()), refs)!!.data!!

        assertEquals(JsonPrimitive(1), data["mushaf"])
        assertEquals(JsonPrimitive(1), data["mushafId"])
        assertEquals(JsonPrimitive("2023-11-14T22:13:20Z"), data["clientUpdatedAt"])
    }

    /** A row and a row restored under its negative twin id (1 and -1) must park on different keys. */
    @Test
    fun parked_session_keys_are_one_per_id() {
        val type = ReadingSessionsType(database)
        val first = type.insert(sessionAt(1, 1, 10))
        type.insert(sessionAt(1, 2, 20), localId = -first)

        type.readAll().keys.forEach(type::park)

        val ayahs = ReadingSessionsRepository(database).sessions().map { it.ayah }
        assertTrue(ayahs.all { it < 0 }, "$ayahs")
        assertEquals(2, ayahs.toSet().size)
    }

    @Test
    fun parked_verse_keys_are_one_per_id() {
        val study = collections.addCollection("Study", 1).localId
        collections.addAyah(study, 2, 255, 1)
        val first = verses.readAll().keys.single()
        verses.insert(verses.readAll().getValue(first).with(Field.int("ayah")(256)), localId = -first)

        verses.readAll().keys.forEach(verses::park)

        val ayahs = collections.ayahs(study).map { it.second }
        assertTrue(ayahs.all { it < 0 }, "$ayahs")
        assertEquals(2, ayahs.toSet().size)
    }

    /**
     * A verse in two highlight colors keeps the newest. A synced membership ranks by the server's addedAt (its
     * base), not its local one, so every device ranks the pair the same way.
     */
    @Test
    fun one_color_per_verse_ranks_synced_memberships_by_the_server_value() {
        val green = collections.addCollection("system:highlights:green", 1).localId
        val yellow = collections.addCollection("system:highlights:yellow", 1).localId
        collections.addAyah(green, 2, 255, 5)
        collections.addAyah(yellow, 2, 255, 3)
        val greenRow = verses.readAll().entries.single { it.value[Field.long("collection")] == green }

        verses.prune(synced = mapOf(greenRow.key to greenRow.value.with(addedAt(1))))

        assertEquals(listOf(2 to 255), collections.ayahs(yellow))
        assertEquals(emptyList(), collections.ayahs(green), "green is newer locally but older on the server")
    }

    @Test
    fun one_color_per_verse_ranks_unsynced_memberships_by_their_own_time() {
        val green = collections.addCollection("system:highlights:green", 1).localId
        val yellow = collections.addCollection("system:highlights:yellow", 1).localId
        collections.addAyah(green, 2, 255, 5)
        collections.addAyah(yellow, 2, 255, 3)
        val study = collections.addCollection("Study", 1).localId
        collections.addAyah(study, 2, 255, 9)

        verses.prune(synced = emptyMap())

        assertEquals(listOf(2 to 255), collections.ayahs(green))
        assertEquals(emptyList(), collections.ayahs(yellow))
        assertEquals(listOf(2 to 255), collections.ayahs(study), "only highlights are one per verse")
    }

    /** An unchanged item takes the server's ignored fields too, so a stale local tie-breaker is refreshed. */
    @Test
    fun an_unchanged_membership_takes_the_server_added_at() {
        val study = collections.addCollection("Study", 1).localId
        collections.addAyah(study, 2, 255, 1)
        val (localId, state) = verses.readAll().entries.single()
        val item = MergeItem(localId, "col$study-bm1", state, state, RemoteItem.Upsert("col$study-bm1", state.with(addedAt(9)), 1), state)

        Merger(verses).merge(item)

        assertEquals(9L, item.working!![addedAt])
        assertEquals(9L, item.base!![addedAt])
    }

    private fun sessionAt(sura: Int, ayah: Int, at: Long): State =
        stateOf(Field.int("sura")(sura), Field.int("ayah")(ayah), Field.long("updatedAt")(at), Field.long("createdAt")(at))
}

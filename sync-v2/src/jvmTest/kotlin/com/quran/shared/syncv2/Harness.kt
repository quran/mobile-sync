package com.quran.shared.syncv2

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.int
import com.quran.shared.syncv2.fake.str
import com.quran.shared.syncv2.types.CollectionsRepository
import com.quran.shared.syncv2.types.ReadingSessionsRepository
import com.quran.shared.syncv2.types.NoteRange
import com.quran.shared.syncv2.types.NotesRepository
import com.quran.shared.syncv2.types.ReadingBookmarksRepository
import com.quran.shared.syncv2.types.ReadingLocation
import com.quran.shared.syncv2.types.syncTypes
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.assertEquals
import kotlin.test.fail

/** One device: a real in-memory database, the real repositories, types and engine, talking HTTP to a [FakeSyncServer]. */
class Device(
    val server: FakeSyncServer,
    var account: String = ACCOUNT,
    private val pageSize: Int = 1000
) : AutoCloseable {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        SyncV2Database.Schema.create(it)
        it.execute(null, "PRAGMA foreign_keys = ON", 0)
    }
    val database = SyncV2Database(driver)
    val sessions = ReadingSessionsRepository(database)
    val collections = CollectionsRepository(database)
    val notes = NotesRepository(database)
    val readingBookmarks = ReadingBookmarksRepository(database)
    private val http = HttpClient(OkHttp) { engine { config { retryOnConnectionFailure(false) } } }
    private val types = syncTypes(database)
    /** The device clock the engine sees (hold expiry); tests move it forward. */
    var now = 1_760_000_000_000L
    var engine = engine(version = "1")
        private set

    /** An app update: the same database under an engine of a new version. */
    fun upgrade(version: String) {
        engine = engine(version)
    }

    private fun engine(version: String) = SyncEngineV2(
        database,
        SyncApi(http, server.url, pageSize) { mapOf("Authorization" to "Bearer $account") },
        types,
        version,
        now = { now }
    )

    /** Every sync asserts that no child was dropped for a missing parent: that is a last resort, never expected. */
    fun sync(push: Boolean = true): SyncResult = runBlocking { engine.sync(account, push) }.also {
        assertEquals(0, it.droppedOrphans, "a child was dropped because its parent was gone: $it")
    }

    fun syncOk(): SyncResult.Success = sync().let { it as? SyncResult.Success ?: fail("sync failed: $it") }

    /**
     * Syncs twice, as the scheduler's follow-up would, then asserts quiescence: one more sync pushes nothing.
     * Returns the last result.
     */
    fun settle(): SyncResult.Success {
        syncOk()
        syncOk()
        val posts = server.postBodies.size
        return syncOk().also {
            assertEquals(posts, server.postBodies.size, "not quiescent: ${server.postBodies.drop(posts)}")
        }
    }

    fun sessionVerses(): List<String> = sessions.sessions().map { "${it.sura}:${it.ayah}" }

    fun noteBodies(): List<String> = notes.notes().map { it.body }

    /** Adds a note on one range, `sura:ayah`; returns its local id. */
    fun addNote(body: String, verse: String, at: Long): Long {
        val (sura, ayah) = verse.split(":").map(String::toInt)
        return notes.addNote(body, listOf(NoteRange(sura, ayah, sura, ayah)), at)
    }

    /** Slot to `sura:ayah` (or `page N`), `-` for a cleared slot. */
    fun readingSlots(): Map<Int, String> = readingBookmarks.readingBookmarks().associate { it.slot to it.location.describe() }

    /** Sets [verse]'s highlight to [color] (one color per verse), as the highlights repository does. */
    fun highlight(sura: Int, ayah: Int, color: String, at: Long) {
        collections.collections().filter { it.name.startsWith(HIGHLIGHTS) }.forEach { collections.removeAyah(it.localId, sura, ayah) }
        val name = "$HIGHLIGHTS$color"
        val collection = collections.byName(name) ?: collections.addCollection(name, at)
        collections.addAyah(collection.localId, sura, ayah, at)
    }

    /** Verse to the highlight colors it has. */
    fun highlights(): Map<String, Set<String>> = collections.collections().filter { it.name.startsWith(HIGHLIGHTS) }
        .flatMap { c -> collections.ayahs(c.localId).map { "${it.first}:${it.second}" to c.name.removePrefix(HIGHLIGHTS) } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    fun collectionNames(): Set<String> = collections.collections().filterNot { it.isDefault }.map { it.name }.toSet()

    fun collectionId(name: String): Long = collections.byName(name)!!.localId

    fun versesIn(name: String): Set<String> =
        versesIn(if (name == FAVORITES) collections.favorites().localId else collections.byName(name)!!.localId)

    fun versesIn(collectionLocalId: Long): Set<String> =
        collections.ayahs(collectionLocalId).map { "${it.first}:${it.second}" }.toSet()

    override fun close() {
        http.close()
        driver.close()
    }

    companion object {
        const val ACCOUNT = "user-a"
        const val FAVORITES = "Favorites"
        const val HIGHLIGHTS = "system:highlights:"
    }
}

fun ReadingLocation?.describe(): String = when {
    this == null -> "-"
    type == "ayah" -> "$key:$verse"
    else -> "$type $key"
}

/** Server-side views and "another device" edits, through the same rules POST uses. */
class Remote(private val server: FakeSyncServer, private val account: String = Device.ACCOUNT) {
    private val data get() = server.account(account)

    fun createCollection(name: String): String = apply("COLLECTION", "CREATE", null, "name" to name)!!
    fun renameCollection(id: String, name: String) = apply("COLLECTION", "UPDATE", id, "name" to name)
    fun deleteCollection(id: String) = apply("COLLECTION", "DELETE", id)
    fun addToCollection(collectionId: String, sura: Int, ayah: Int, at: Long? = null) = apply(
        "COLLECTION_BOOKMARK", "CREATE", null, "collectionId" to collectionId, "key" to sura, "verseNumber" to ayah, "type" to "ayah", "mushaf" to 4,
        *listOfNotNull(at?.let { "clientCreatedAt" to it }, at?.let { "clientUpdatedAt" to it }).toTypedArray()
    )
    fun removeFromCollection(collectionId: String, sura: Int, ayah: Int) =
        apply("COLLECTION_BOOKMARK", "DELETE", null, "collectionId" to collectionId, "key" to sura, "verseNumber" to ayah, "type" to "ayah", "mushaf" to 4)

    /** quran.com's "bookmark this verse": a BOOKMARK CREATE, which lands in Favorites. */
    fun bookmark(sura: Int, ayah: Int): String = apply("BOOKMARK", "CREATE", null, "key" to sura, "verseNumber" to ayah, "type" to "ayah", "mushaf" to 4)!!
    fun deleteBookmark(id: String) = apply("BOOKMARK", "DELETE", id)

    fun createSession(sura: Int, ayah: Int, updatedAt: Long): String =
        apply("READING_SESSION", "CREATE", null, "chapterNumber" to sura, "verseNumber" to ayah, "clientUpdatedAt" to updatedAt)!!
    fun updateSession(id: String, sura: Int, ayah: Int, updatedAt: Long) =
        apply("READING_SESSION", "UPDATE", id, "chapterNumber" to sura, "verseNumber" to ayah, "clientUpdatedAt" to updatedAt)
    fun deleteSession(id: String) = apply("READING_SESSION", "DELETE", id)

    fun collectionNames(): Set<String> = data.collections.values.map { it.name }.toSet()
    fun collectionIdOf(name: String): String = data.collections.entries.single { it.value.name == name }.key
    fun sessionVerses(): Set<String> = data.sessions.values.map { "${it.chapter}:${it.verse}" }.toSet()
    fun sessionIdAt(sura: Int, ayah: Int): String = data.sessions.entries.single { it.value.chapter == sura && it.value.verse == ayah }.key

    fun versesIn(name: String): Set<String> {
        if (name == Device.FAVORITES) return data.bookmarks.values.filter { it.inDefault }.map { "${it.key}:${it.verse}" }.toSet()
        val collectionId = collectionIdOf(name)
        return data.memberships.values.filter { it.collectionId == collectionId }
            .map { data.bookmarks.getValue(it.bookmarkId).let { b -> "${b.key}:${b.verse}" } }.toSet()
    }

    fun bookmarkCount(): Int = data.bookmarks.size
    fun noteBodies(): List<String> = data.notes.values.map { it.body }
    fun noteIds(): Set<String> = data.notes.keys
    fun noteRanges(): List<List<String>> = data.notes.values.map { it.ranges }
    fun createNote(body: String, vararg ranges: String, createdAt: Long = 1_750_000_000_000L): String = apply(
        "NOTE", "CREATE", null, "body" to body, "ranges" to JsonArray(ranges.map(::JsonPrimitive)), "clientCreatedAt" to createdAt
    )!!
    fun editNote(id: String, body: String, updatedAt: Long? = null) =
        apply("NOTE", "UPDATE", id, *listOfNotNull("body" to body, updatedAt?.let { "clientUpdatedAt" to it }).toTypedArray())
    fun deleteNote(id: String) = apply("NOTE", "DELETE", id)
    /** QuranReflect archives a note (a 30-day trash): it stays on the server with `archivedAt`. */
    fun archiveNote(id: String) {
        data.notes[id] = data.notes.getValue(id).copy(archivedAt = data.now())
        data.log("NOTE", id, "UPDATE")
    }
    fun unarchiveNote(id: String) {
        data.notes[id] = data.notes.getValue(id).copy(archivedAt = null)
        data.log("NOTE", id, "UPDATE")
    }
    fun archivedNoteIds(): Set<String> = data.notes.filterValues { it.archivedAt != null }.keys

    fun setReadingBookmark(slot: Int, sura: Int, ayah: Int, updatedAt: Long, metadata: JsonObject? = null): String = apply(
        "READING_BOOKMARK", "CREATE", null, "slot" to slot, "type" to "ayah", "key" to sura, "verseNumber" to ayah,
        "mushafId" to 1, "clientUpdatedAt" to updatedAt, *listOfNotNull(metadata?.let { "metadata" to it }).toTypedArray()
    )!!
    /** The web renames a slot: its UPDATE replaces the whole slot, so it sends the location it has, too. */
    fun renameReadingBookmark(slot: Int, name: String, updatedAt: Long) {
        val current = data.readingBookmarks.values.single { it.slot == slot }
        apply(
            "READING_BOOKMARK", "UPDATE", readingBookmarkId(slot), "slot" to slot, "name" to name, "type" to current.type!!,
            "key" to current.key!!, *listOfNotNull(current.verse?.let { "verseNumber" to it }).toTypedArray(),
            "mushafId" to 1, "clientUpdatedAt" to updatedAt, *listOfNotNull(current.metadata?.let { "metadata" to it }).toTypedArray()
        )
    }
    fun readingBookmarkMetadata(slot: Int): JsonObject? = data.readingBookmarks.values.single { it.slot == slot }.metadata
    fun setReadingPage(slot: Int, page: Int, mushafId: Int, updatedAt: Long): String = apply(
        "READING_BOOKMARK", "CREATE", null, "slot" to slot, "type" to "page", "key" to page, "mushafId" to mushafId, "clientUpdatedAt" to updatedAt
    )!!
    fun clearReadingBookmark(slot: Int, updatedAt: Long) = apply(
        "READING_BOOKMARK", "UPDATE", readingBookmarkId(slot), "slot" to slot, "mushafId" to JsonNull, "type" to JsonNull,
        "group" to JsonNull, "key" to JsonNull, "verseNumber" to JsonNull, "clientUpdatedAt" to updatedAt
    )
    fun readingSlots(): Map<Int, String> = data.readingBookmarks.values.associate {
        it.slot to if (it.type == null) "-" else if (it.type == "ayah") "${it.key}:${it.verse}" else "${it.type} ${it.key}"
    }
    fun readingBookmarkId(slot: Int): String = data.readingBookmarks.entries.single { it.value.slot == slot }.key
    fun readingBookmarkName(slot: Int): String? = data.readingBookmarks.values.single { it.slot == slot }.name
    /** The verse of the bookmark carrying `isReading` (slot 1's location), if any. */
    fun readingVerse(): String? = data.bookmarks.values.singleOrNull { it.isReading }?.let { "${it.key}:${it.verse}" }

    /** The row is gone, but its log stays: GET sends it as `{}` (a resource that vanished between queries). */
    fun vanish(resource: String, id: String) {
        when (resource) {
            "READING_BOOKMARK" -> data.readingBookmarks.remove(id)
            "NOTE" -> data.notes.remove(id)
            else -> error(resource)
        }
        data.log(resource, id, "UPDATE")
    }

    private fun apply(resource: String, type: String, resourceId: String?, vararg fields: Pair<String, Any>): String? =
        server.remote(account, buildJsonObject {
            put("resource", resource)
            put("type", type)
            resourceId?.let { put("resourceId", it) }
            put("data", JsonObject(fields.associate { (key, value) ->
                key to when (value) {
                    is JsonElement -> value
                    is Number -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
            }))
        }).single()
}

fun FakeSyncServer.mutationTypesOfLastPost(): List<String> = postBodies.last()["mutations"]!!.jsonArray
    .map { "${it.jsonObject["type"]!!.jsonPrimitive.content} ${it.jsonObject["resource"]!!.jsonPrimitive.content}" }

fun FakeSyncServer.postedMutationCount(): Int = postBodies.sumOf { it["mutations"]!!.jsonArray.size }

/** Whether a POSTed mutation is a reading session at the verse (for `rejectMutation`). */
fun JsonObject.isSessionAt(sura: Int, ayah: Int): Boolean {
    val data = this["data"] as? JsonObject ?: return false
    return str("resource") == "READING_SESSION" && data.int("chapterNumber") == sura && data.int("verseNumber") == ayah
}

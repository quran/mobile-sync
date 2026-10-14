package com.quran.shared.syncv2.types

import com.quran.shared.syncv2.Field
import co.touchlab.kermit.Logger
import com.quran.shared.syncv2.Change
import com.quran.shared.syncv2.OutgoingMutation
import com.quran.shared.syncv2.ParentRef
import com.quran.shared.syncv2.PostedMutation
import com.quran.shared.syncv2.Refs
import com.quran.shared.syncv2.RemoteEntry
import com.quran.shared.syncv2.RemoteItem
import com.quran.shared.syncv2.State
import com.quran.shared.syncv2.SyncType
import com.quran.shared.syncv2.db.HighlightAyahs
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.bool
import com.quran.shared.syncv2.get
import com.quran.shared.syncv2.int
import com.quran.shared.syncv2.epochMillis
import com.quran.shared.syncv2.isoTime
import com.quran.shared.syncv2.stateOf
import com.quran.shared.syncv2.string
import com.quran.shared.syncv2.with
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * "Verse in collection": one type for the server's BOOKMARK, COLLECTION_BOOKMARK and Favorites flag.
 * Natural key = (collection, verse). Every row maps to a key-based COLLECTION_BOOKMARK, Favorites included
 * (`collectionId = __default__`), which the server upserts and which creates the bookmark itself.
 * BOOKMARK is never pushed; GET BOOKMARK entries feed this type's bookmark-id-to-verse index
 * (`remote_ayah_bookmark`), used to read memberships, which carry only `{collectionId, bookmarkId}`, and stand for
 * the Favorites memberships the server never logged (see [unloggedFavorites]).
 * Ayah bookmarks are mushaf-agnostic: one mushaf, sent as `mushaf` (the key the server's membership code reads) and
 * `mushafId`. [ADDED_AT] is when the verse was added (`clientUpdatedAt` on the server): a tie-breaker, not data.
 * Highlights (`system:highlights:<color>`) keep one color per verse, the newest, on remote changes too (see [prune]).
 */
class CollectionAyahsType(private val database: SyncV2Database, collections: CollectionsType) : SyncType {
    private val queries get() = database.collectionsQueries
    private val logger = Logger.withTag("SyncV2CollectionAyahs")
    override val name = "COLLECTION_AYAH"
    override val parent = ParentRef(COLLECTION, collections)

    override val ignoredFields = setOf(ADDED_AT.name)

    override fun naturalKey(state: State) = "${state[COLLECTION]}:${state[SURA]}:${state[AYAH]}"
    override fun clock(state: State) = state[ADDED_AT]

    override fun readAll(): Map<Long, State> = queries.collectionAyahsAll().executeAsList().associate {
        it.local_id to stateOf(COLLECTION(it.collection_local_id), SURA(it.sura.toInt()), AYAH(it.ayah.toInt()), ADDED_AT(it.created_at))
    }

    override fun insert(state: State, localId: Long?): Long {
        val collection = state[COLLECTION]
        val sura = state[SURA].toLong()
        val ayah = state[AYAH].toLong()
        if (localId != null) {
            queries.collectionAyahInsertWithId(localId, collection, sura, ayah, clock(state))
            return localId
        }
        queries.collectionAyahInsert(collection, sura, ayah, clock(state))
        return queries.lastInsertId().executeAsOne()
    }

    override fun update(localId: Long, state: State) {
        queries.collectionAyahUpdate(state[COLLECTION], state[SURA].toLong(), state[AYAH].toLong(), clock(state), localId)
    }

    override fun park(localId: Long) {
        queries.collectionAyahPark(localId)
    }
    override fun delete(localId: Long) {
        queries.collectionAyahDelete(localId)
    }
    override fun wipe() {
        queries.collectionAyahsDeleteAll()
        queries.remoteAyahBookmarksDeleteAll()
    }

    /**
     * First records which verse each ayah bookmark id is, from GET BOOKMARK entries (idempotent: the engine may map
     * one GET more than once), then reads the memberships, Favorites' unlogged ones included ([unloggedFavorites]).
     */
    override fun fromRemote(entries: List<RemoteEntry>, refs: Refs): List<RemoteItem> {
        entries.filter { it.resource == BOOKMARK && it.type != "DELETE" && it.resourceId != null && isAyah(it) }.forEach { entry ->
            val sura = entry.data.int("key") ?: return@forEach
            val ayah = entry.data.int("verseNumber") ?: return@forEach
            rememberBookmark(entry.resourceId!!, sura, ayah)
        }
        val memberships = entries.filter { it.resource == RESOURCE }
        return (memberships + unloggedFavorites(entries, memberships)).mapNotNull { fromRemote(it, refs) }
    }

    /**
     * Favorites memberships the server never logged. The backend logs a `__default__` membership only for bookmarks
     * written since quran.com-users-backend#1491; an older bookmark in Favorites that has not changed since (and that
     * the default-collection backfill has not reached) appears only as a BOOKMARK whose data has
     * `isInDefaultCollection`. So an ayah BOOKMARK with no `__default__` entry in the same GET stands for its
     * Favorites membership, under the remote id and client times a logged one has: in Favorites while the flag is
     * true, out when it is false or the bookmark is deleted. A logged entry always wins: it is the server's own record.
     * Favorites is still pushed by key, as a COLLECTION_BOOKMARK `__default__`.
     */
    private fun unloggedFavorites(entries: List<RemoteEntry>, memberships: List<RemoteEntry>): List<RemoteEntry> {
        val logged = memberships.filter { it.data.string("collectionId") == FAVORITES_ID }
            .mapNotNullTo(mutableSetOf()) { it.data.string("bookmarkId") }
        return entries.filter { it.resource == BOOKMARK && it.resourceId != null && it.resourceId !in logged }.mapNotNull { entry ->
            val inFavorites = entry.type != "DELETE" && (entry.data.bool("isInDefaultCollection") ?: return@mapNotNull null)
            if (inFavorites && !isAyah(entry)) return@mapNotNull null
            val data = buildJsonObject {
                put("collectionId", FAVORITES_ID)
                put("bookmarkId", entry.resourceId)
                if (inFavorites) CLIENT_TIMES.forEach { key -> entry.data[key]?.let { put(key, it) } }
            }
            RemoteEntry(RESOURCE, null, if (inFavorites) "CREATE" else "DELETE", data, entry.timestamp)
        }
    }

    private fun isAyah(bookmark: RemoteEntry) =
        (bookmark.data.string("bookmarkType") ?: bookmark.data.string("type")).equals("ayah", ignoreCase = true)

    private fun fromRemote(entry: RemoteEntry, refs: Refs): RemoteItem? {
        val collectionId = entry.data.string("collectionId") ?: return null
        val bookmarkId = entry.data.string("bookmarkId") ?: return null
        val remoteId = "$collectionId-$bookmarkId"
        if (entry.type == "DELETE") return RemoteItem.Deleted(remoteId, entry.timestamp)
        // Unknown bookmark ids are page, surah or juz bookmarks, which mobile doesn't sync.
        val verse = queries.remoteAyahBookmarkGet(bookmarkId).executeAsOneOrNull() ?: run {
            logger.d { "Skipping membership $remoteId: bookmark $bookmarkId is not a known ayah bookmark" }
            return null
        }
        // Unknown before the collections of this GET are written; unknown after, it is not in this GET's collections.
        val collection = refs.localId(parent.type, collectionId)
            ?: return RemoteItem.Unresolved(remoteId, "collection $collectionId is unknown", entry.timestamp)
        val addedAt = entry.data.epochMillis("clientUpdatedAt") ?: entry.data.epochMillis("clientCreatedAt") ?: entry.timestamp
        return RemoteItem.Upsert(
            remoteId,
            stateOf(COLLECTION(collection), SURA(verse.sura.toInt()), AYAH(verse.ayah.toInt()), ADDED_AT(addedAt)),
            entry.timestamp
        )
    }

    /** Key-based: no UPDATE (a verse in a collection has nothing to change), and DELETE by key, not id. */
    override fun mutation(change: Change, refs: Refs): OutgoingMutation? = when (change) {
        is Change.Create -> payload(change.state, refs)?.let { data ->
            val addedAt = isoTime(clock(change.state))
            OutgoingMutation("CREATE", RESOURCE, null, data.with("clientCreatedAt" to JsonPrimitive(addedAt), "clientUpdatedAt" to JsonPrimitive(addedAt)))
        }
        is Change.Update -> null
        is Change.Delete -> payload(change.base, refs)?.let { OutgoingMutation("DELETE", RESOURCE, null, it) }
    }

    /** The composite id `collectionId-bookmarkId` also tells which bookmark id the verse has. */
    override fun bindCreated(state: State, response: PostedMutation): String? {
        val remoteId = response.resourceId?.takeIf { it.contains('-') } ?: return null
        rememberBookmark(remoteId.substringAfter('-'), state[SURA], state[AYAH])
        return remoteId
    }

    override fun validate(change: Change): String? =
        if (change is Change.Delete || Verses.exists(change.state[SURA], change.state[AYAH])) null else "INVALID_VERSE"

    private fun payload(state: State, refs: Refs) = refs.remoteId(parent.type, state[COLLECTION])?.let { collectionId ->
        buildJsonObject {
            put("collectionId", collectionId)
            put("type", "ayah")
            put("key", state[SURA])
            put("verseNumber", state[AYAH])
            put("mushaf", MUSHAF)
            put("mushafId", MUSHAF)
        }
    }

    /**
     * One color per verse: when a verse is in more than one highlight collection (another device changed its color
     * while this one did too), the newest membership stays and the others go; a synced one is pushed as a DELETE.
     * A synced membership ranks by the server's `addedAt` (its base), an unsynced one by its own; ties go to the
     * color name. So every device ranks one pair the same way and they converge on one color: ranking by a
     * local value could make two devices each remove the other's color, leaving none.
     */
    override fun prune(synced: Map<Long, State>) {
        queries.highlightAyahs().executeAsList()
            .groupBy { it.sura to it.ayah }.values
            .flatMap { colors ->
                colors.sortedWith(compareByDescending<HighlightAyahs> { synced[it.local_id]?.get(ADDED_AT) ?: it.created_at }.thenBy { it.name_key })
                    .drop(1)
            }
            .forEach {
                logger.i { "Verse ${it.sura}:${it.ayah} has a newer highlight than ${it.name_key}; removing it" }
                queries.collectionAyahDelete(it.local_id)
            }
    }

    private fun rememberBookmark(bookmarkId: String, sura: Int, ayah: Int) {
        queries.remoteAyahBookmarkPut(bookmarkId, sura.toLong(), ayah.toLong())
    }

    private companion object {
        val COLLECTION = Field.long("collection")
        val SURA = Field.int("sura")
        val AYAH = Field.int("ayah")
        val ADDED_AT = Field.long("addedAt")
        const val RESOURCE = "COLLECTION_BOOKMARK"
        const val BOOKMARK = "BOOKMARK"
        const val FAVORITES_ID = CollectionsType.DEFAULT_COLLECTION_ID
        val CLIENT_TIMES = listOf("clientCreatedAt", "clientUpdatedAt")
        const val MUSHAF = 1
    }
}

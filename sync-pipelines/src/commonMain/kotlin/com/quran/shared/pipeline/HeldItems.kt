package com.quran.shared.pipeline

import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import com.quran.shared.mutations.ServerTextLimit
import com.quran.shared.syncv2.HeldItem
import com.quran.shared.syncv2.SyncEngineV2
import com.quran.shared.syncv2.SyncStore
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.CollectionAyahsType
import com.quran.shared.syncv2.types.CollectionVerse
import com.quran.shared.syncv2.types.CollectionsType
import com.quran.shared.syncv2.types.NotesType
import com.quran.shared.syncv2.types.syncTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn

/**
 * [QuranDataService.heldItems] and [QuranDataService.unsyncedItemCount], both from one [UnsyncedSnapshot] of
 * [database], read again on every write to `sync_item`, `sync_meta` or a domain table, as the next push would see it.
 *
 * Every collector shares one reader in [scope], as each read is a transaction (the writer lock on Android and iOS).
 * It reads only while someone collects, off the main thread, and drops its snapshot once it stops; a collector that
 * joins as it stops may get that snapshot first, until the restarted reader's fresh read.
 *
 * A read that fails (a stored state that doesn't parse, a database error) is logged and skipped: collectors keep the
 * last snapshot, and the next write reads again. It never reaches [scope], where it would end the reader for everyone.
 */
internal class HeldItems(private val database: SyncV2Database, scope: CoroutineScope) {
    private val logger = Logger.withTag("HeldItems")
    private val store = SyncStore(database)
    private val types = syncTypes(database)
    private val collectionAyahs = types.filterIsInstance<CollectionAyahsType>().single()
    private val notesType = types.filterIsInstance<NotesType>().single()
    private val collectionsType = types.filterIsInstance<CollectionsType>().single()

    val snapshots: Flow<UnsyncedSnapshot> = database.sync_itemQueries.holdInputs().asFlow()
        .mapNotNull { readOrNull() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
        .shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

    val flow: Flow<List<HeldSyncItem>> = snapshots.map { it.held }.distinctUntilChanged()

    val unsyncedCount: Flow<Int> = snapshots.map { it.held.size + it.pendingCount }.distinctUntilChanged()

    private fun readOrNull(): UnsyncedSnapshot? = try {
        read()
    } catch (e: Exception) {
        // The class only: a message may quote what was read.
        logger.w { "Could not read the unsynced items (${e::class.simpleName}); keeping the last snapshot" }
        null
    }

    /** In one transaction, so the held items, the rows they are mapped with and the pending items agree. */
    fun read(): UnsyncedSnapshot = database.transactionWithResult {
        val unsynced = SyncEngineV2.unsynced(database, types)
        UnsyncedSnapshot(map(unsynced.held), unsynced.pending.size)
    }

    /** Maps the engine's items; a waiting item's [HeldSyncItem.waitingOn] is the mapped held item it names. */
    private fun map(items: List<HeldItem>): List<HeldSyncItem> {
        val roots = mutableMapOf<Pair<String, Long>, HeldSyncItem>()
        val (waiting, held) = items.partition { it.waitingOn != null }
        val mappedHeld = held.mapNotNull { item -> toPublic(item, waitingOn = null)?.also { roots[item.type to item.localId] = it } }
        val mappedWaiting = waiting.mapNotNull { item ->
            val root = item.waitingOn?.let { roots[it.type to it.localId] }
            if (root == null) logger.w { "${item.type} ${item.localId} waits on an item that isn't held" }
            toPublic(item, root)
        }
        return mappedHeld + mappedWaiting
    }

    private fun toPublic(item: HeldItem, waitingOn: HeldSyncItem?): HeldSyncItem? {
        val (type, id, collectionId) = identify(item) ?: run {
            logger.w { "Skipping held ${item.type} ${item.localId}: unknown type or no such item" }
            return null
        }
        val hold = heldReason(item.reason) { text(item) }
        if (hold.unknown) logger.w { "Unknown hold reason for ${item.type} ${item.localId}" }
        return HeldSyncItem(type, id, collectionId, hold.reason, waitingOn, hold.httpStatus, hold.serverCode)
    }

    private fun identify(item: HeldItem): Triple<SyncResourceType, String, String?>? = when (item.type) {
        NOTE -> Triple(SyncResourceType.NOTE, item.localId.toString(), null)
        COLLECTION -> Triple(SyncResourceType.COLLECTION, item.localId.toString(), null)
        READING_SESSION -> Triple(SyncResourceType.READING_SESSION, item.localId.toString(), null)
        READING_BOOKMARK -> Triple(SyncResourceType.READING_BOOKMARK, item.localId.toString(), null)
        COLLECTION_AYAH -> verse(item.localId)?.let {
            Triple(SyncResourceType.COLLECTION_BOOKMARK, "${it.sura}:${it.ayah}", it.collectionLocalId.toString())
        }
        else -> null
    }

    /** The membership's verse: its row, or for a held DELETE (no row) its base. */
    private fun verse(localId: Long): CollectionVerse? =
        database.collectionsQueries.collectionAyahGet(localId).executeAsOneOrNull()
            ?.let { CollectionVerse(it.collection_local_id, it.sura.toInt(), it.ayah.toInt()) }
            ?: store.row(COLLECTION_AYAH, localId)?.base?.let(collectionAyahs::verseOf)

    /**
     * The text a length hold measured: a note's body, a collection's or reading bookmark's name. It is the item's row,
     * or without one (it was deleted) the state the hold recorded; null [HeldText.text] when neither can be read.
     */
    private fun text(item: HeldItem): HeldText? = when (item.type) {
        NOTE -> HeldText(
            database.notesQueries.get(item.localId).executeAsOneOrNull()?.body ?: heldState(item)?.let(notesType::bodyOf),
            ServerTextLimit.NOTE_BODY
        )
        COLLECTION -> HeldText(
            database.collectionsQueries.collectionGet(item.localId).executeAsOneOrNull()?.name
                ?: heldState(item)?.let(collectionsType::nameOf),
            ServerTextLimit.COLLECTION_NAME
        )
        // A reading bookmark name has only a maximum.
        READING_BOOKMARK -> HeldText(null, null)
        else -> null
    }

    private fun heldState(item: HeldItem) = store.row(item.type, item.localId)?.heldState

    companion object {
        const val NOTE = "NOTE"
        const val COLLECTION = "COLLECTION"
        const val COLLECTION_AYAH = "COLLECTION_AYAH"
        const val READING_SESSION = "READING_SESSION"
        const val READING_BOOKMARK = "READING_BOOKMARK"
    }
}

/** The held items as [QuranDataService.heldItems] lists them, and how many other items the next push sends. */
internal data class UnsyncedSnapshot(val held: List<HeldSyncItem>, val pendingCount: Int)

/**
 * A length hold's text and the limit it broke; a null [limit] means the type has only a maximum. A null [text] (it
 * couldn't be read) measures as empty.
 */
internal data class HeldText(val text: String?, val limit: ServerTextLimit?)

/** A hold reason as the public API reports it. [unknown]: the engine's reason isn't one this mapping knows. */
internal data class PublicHold(
    val reason: HeldReason,
    val httpStatus: Int? = null,
    val serverCode: String? = null,
    val unknown: Boolean = false
)

private val SERVER_HOLD = Regex("^(\\d{3})(?: (\\S+))?$")

/**
 * Maps an engine hold reason to [HeldReason]. A length reason (`BODY_LENGTH`, `NAME_LENGTH`) becomes [HeldReason.TOO_SHORT]
 * when [text] measures below its minimum (a text that couldn't be read measures as empty), and [HeldReason.TOO_LONG]
 * when it doesn't or the type has only a maximum; on a type with no known text it is unknown, as below. A server
 * hold (`"<status> <code>"`) becomes [HeldReason.REJECTED_BY_SERVER] with both; any other reason too, with neither,
 * and [PublicHold.unknown] set.
 */
internal fun heldReason(reason: String, text: () -> HeldText?): PublicHold = when (reason) {
    "BODY_LENGTH", "NAME_LENGTH" -> {
        val held = text() ?: return PublicHold(HeldReason.REJECTED_BY_SERVER, unknown = true)
        val limit = held.limit
        val tooShort = limit != null && limit.serverLength(held.text.orEmpty()) < limit.minLength
        PublicHold(if (tooShort) HeldReason.TOO_SHORT else HeldReason.TOO_LONG)
    }
    "INVALID_RANGE" -> PublicHold(HeldReason.INVALID_RANGE)
    "INVALID_VERSE" -> PublicHold(HeldReason.INVALID_VERSE)
    "INVALID_LOCATION" -> PublicHold(HeldReason.INVALID_LOCATION)
    "INVALID_SLOT" -> PublicHold(HeldReason.INVALID_SLOT)
    "METADATA_SIZE" -> PublicHold(HeldReason.TOO_MUCH_METADATA)
    "RESERVED_NAME" -> PublicHold(HeldReason.RESERVED_NAME)
    "READ_ONLY" -> PublicHold(HeldReason.READ_ONLY)
    HeldItem.WAITING_ON_PARENT -> PublicHold(HeldReason.WAITING_ON_PARENT)
    HeldItem.WAITING_ON_KEY -> PublicHold(HeldReason.WAITING_ON_OTHER_ITEM)
    HeldItem.NEVER_LINKED -> PublicHold(HeldReason.NOT_YET_LINKED)
    else -> SERVER_HOLD.matchEntire(reason)?.let {
        PublicHold(HeldReason.REJECTED_BY_SERVER, it.groupValues[1].toInt(), it.groupValues[2].ifEmpty { null })
    } ?: PublicHold(HeldReason.REJECTED_BY_SERVER, unknown = true)
}

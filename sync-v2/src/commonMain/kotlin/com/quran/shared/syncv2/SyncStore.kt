package com.quran.shared.syncv2

import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.db.Sync_item
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A `sync_item` row. */
data class SyncRow(
    val localId: Long,
    val remoteId: String?,
    val base: State?,
    val heldReason: String? = null,
    val heldState: State? = null,
    /** When a hold caused by a server error expires (epoch ms); null for holds that wait for an edit. */
    val heldUntil: Long? = null
) {
    fun unheld() = copy(heldReason = null, heldState = null, heldUntil = null)
}

/**
 * The exact batch sent in a POST, persisted before sending so a lost response can be resolved. [landed]: the POST
 * is known to have landed (it answered, but its answers couldn't be matched to its items), so no proof is needed.
 */
@Serializable
data class Outbox(val account: String, val head: Long, val items: List<OutboxItem>, val landed: Boolean = false)

@Serializable
data class OutboxItem(
    val type: String,
    val localId: Long,
    val kind: String,
    val state: JsonObject?,
    val remoteId: String?
)

/**
 * In how many runs one item failed with [status], for errors one run can't pin on an item. It decides when such an
 * item is held, and for how long. Cleared when the item lands.
 */
@Serializable
data class ItemFailure(val status: Int, val count: Int)

/** The only sync state besides the domain rows: per-item bases, the token, the account and the outbox. */
class SyncStore(val database: SyncV2Database) {
    private val queries get() = database.sync_itemQueries

    fun rows(type: String): Map<Long, SyncRow> =
        queries.all(type).executeAsList().associate { it.local_id to it.toRow() }

    fun row(type: String, localId: Long): SyncRow? = queries.get(type, localId).executeAsOneOrNull()?.toRow()

    fun rowByRemote(type: String, remoteId: String): SyncRow? =
        queries.byRemote(type, remoteId).executeAsOneOrNull()?.toRow()

    /** Writes [row], or removes it when it carries nothing. A remote id moves to its new owner. */
    fun put(type: String, row: SyncRow) {
        if (row.remoteId == null && row.base == null && row.heldReason == null) {
            queries.delete(type, row.localId)
            return
        }
        row.remoteId?.let { remoteId ->
            rowByRemote(type, remoteId)?.takeIf { it.localId != row.localId }?.let { queries.delete(type, it.localId) }
        }
        queries.upsert(
            type, row.localId, row.remoteId, row.base?.let(::encode),
            row.heldReason, row.heldState?.let(::encode), row.heldUntil
        )
    }

    /**
     * A local id for a tombstone of a remote item that never had a domain row. Negative, so it can't collide with
     * the domain tables' AUTOINCREMENT ids, and below every id in use: [domainIds] holds the type's domain rows,
     * which may keep a negative id after their sync row is gone (a restored tombstone, then edited and recreated).
     */
    fun newTombstoneId(type: String, domainIds: Set<Long>): Long =
        minOf(0L, rows(type).keys.minOrNull() ?: 0L, domainIds.minOrNull() ?: 0L) - 1

    fun remove(type: String, localId: Long) {
        queries.delete(type, localId)
    }

    var token: Long
        get() = meta(TOKEN)?.toLong() ?: 0L
        set(value) {
            queries.metaSet(TOKEN, value.toString())
        }

    var account: String?
        get() = meta(ACCOUNT)
        set(value) {
            if (value == null) queries.metaDelete(ACCOUNT) else queries.metaSet(ACCOUNT, value)
        }

    /** Bumped by every wipe; a sync commits only under the epoch it started with (see [Generation]). */
    var epoch: Long
        get() = meta(EPOCH)?.toLong() ?: 0L
        set(value) {
            queries.metaSet(EPOCH, value.toString())
        }

    /** The app and library version the holds were decided under; a new version re-evaluates them. */
    var version: String?
        get() = meta(VERSION)
        set(value) {
            if (value == null) queries.metaDelete(VERSION) else queries.metaSet(VERSION, value)
        }

    /**
     * The batches POSTed since the last GET whose outcome is unknown (a 5xx, a lost response, a crash). More than
     * one only after a 5xx: a later POST must not replace a batch that may have landed until a GET resolved it.
     */
    var outboxes: List<Outbox>
        get() = meta(OUTBOX)?.let { decode(outboxesSerializer, it, "outbox") }.orEmpty()
        set(value) {
            if (value.isEmpty()) {
                queries.metaDelete(OUTBOX)
            } else {
                queries.metaSet(OUTBOX, json.encodeToString(outboxesSerializer, value))
            }
        }

    /** Failures by `type:localId` (see [ItemFailure]). */
    var failures: Map<String, ItemFailure>
        get() = meta(FAILURES)?.let { decode(failuresSerializer, it, "failures") }.orEmpty()
        set(value) {
            if (value.isEmpty()) queries.metaDelete(FAILURES) else queries.metaSet(FAILURES, json.encodeToString(failuresSerializer, value))
        }

    fun wipe() {
        queries.deleteAll()
        queries.metaDeleteAll()
    }

    private fun meta(key: String): String? = queries.metaGet(key).executeAsOneOrNull()

    private fun Sync_item.toRow() = SyncRow(
        localId = local_id,
        remoteId = remote_id,
        base = base_json?.let(::decode),
        heldReason = held_reason,
        heldState = held_json?.let(::decode),
        heldUntil = held_until
    )

    companion object {
        private const val TOKEN = "token"
        private const val ACCOUNT = "account"
        private const val OUTBOX = "outbox"
        private const val VERSION = "version"
        private const val EPOCH = "epoch"
        private const val FAILURES = "failures"
        private val failuresSerializer = MapSerializer(String.serializer(), ItemFailure.serializer())
        private val outboxesSerializer = ListSerializer(Outbox.serializer())
        val json = Json { ignoreUnknownKeys = true }
        fun encode(state: State): String = json.encodeToString(JsonObject.serializer(), state)
        fun decode(text: String): State = decode(JsonObject.serializer(), text, "state")

        /**
         * Decodes stored JSON. Text that doesn't parse fails without the parser's error, which quotes the text (a
         * note's body, a collection's name) into whatever logs the failure.
         */
        private fun <T> decode(deserializer: DeserializationStrategy<T>, text: String, what: String): T = try {
            json.decodeFromString(deserializer, text)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one; neither its message nor its cause may go along.
            throw IllegalStateException("The stored $what is not valid JSON")
        }
    }
}

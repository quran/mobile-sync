package com.quran.shared.pipeline.integration.server

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A backend error. Like the backend's `BaseError`, it has `details` (`{}` unless set); only the
 * untyped 500 "Something went wrong" has none.
 */
class HttpError(
    val status: Int,
    val code: String,
    override val message: String,
    val details: JsonObject? = JsonObject(emptyMap()),
    /** The index of the mutation that caused it, reported only when [ServerFaults.mutationIndex] is on. */
    var mutationIndex: Int? = null
) : Exception(message)

/**
 * An untyped error the real backend throws (for example a note range naming a verse that does not
 * exist). The backend answers it with 500 `ServerError` "Something went wrong". Any other
 * exception inside the fake is a bug in the fake and fails the scenario.
 */
class BackendCrash(message: String) : Exception(message)

internal fun validationError(message: String) = HttpError(422, "ValidationError", message)
internal fun notFound(message: String) = HttpError(404, "NotFoundError", message)
internal fun forbidden(message: String = "You don't have permission") = HttpError(403, "ForbiddenError", message)

/** One `ResourceLog` row: what GET reports for a resource. Timestamps are epoch milliseconds. */
data class LogRow(
    val resource: String,
    val resourceId: String,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null,
    /** Insertion order, the tie-breaker for equal `lastChangeAt`. */
    val seq: Long
) {
    val lastChangeAt: Long get() = maxOf(createdAt, updatedAt ?: 0, deletedAt ?: 0)
    val isDeleted: Boolean get() = deletedAt != null
}

data class ServerCollection(
    val id: String,
    val name: String,
    val isPrivate: Boolean = true,
    /** `metadata.source`, set for collections an older QDC app created. */
    val source: String? = null,
    val clientCreatedAt: Long,
    val clientUpdatedAt: Long
)

data class ServerBookmark(
    val id: String,
    val type: String,
    val group: String,
    val key: Int,
    val verse: Int?,
    val inDefault: Boolean,
    val isReading: Boolean? = null,
    val clientCreatedAt: Long,
    val clientUpdatedAt: Long
)

data class ServerMembership(
    val collectionId: String,
    val bookmarkId: String,
    val clientCreatedAt: Long,
    val clientUpdatedAt: Long
) {
    val id: String get() = "$collectionId-$bookmarkId"
}

data class ServerNote(
    val id: String,
    val body: String,
    val ranges: List<String>,
    val source: String,
    val saveToQR: Boolean = false,
    val archivedAt: Long? = null,
    val attachedEntities: List<JsonObject> = emptyList(),
    val clientCreatedAt: Long,
    val clientUpdatedAt: Long
)

data class ServerSession(
    val id: String,
    val chapter: Int,
    val verse: Int,
    val clientCreatedAt: Long,
    val clientUpdatedAt: Long
)

data class ServerReadingBookmark(
    val id: String,
    val slot: Int,
    val name: String? = null,
    val type: String? = null,
    val group: String? = null,
    val key: Int? = null,
    val verse: Int? = null,
    /** The written `mushafId`, which GET returns only with [FakeAccount.keepsMushafId]. */
    val mushafId: Int? = null,
    val metadata: JsonObject? = null,
    val clientCreatedAt: Long? = null,
    val clientUpdatedAt: Long? = null
) {
    val isPlaced: Boolean get() = type != null
}

/**
 * One user's data on the fake server, plus the user's `ResourceLog`.
 *
 * Entities are immutable values in insertion-ordered maps, so a snapshot for an atomic POST is a
 * copy of the maps.
 */
class FakeAccount internal constructor(
    val name: String,
    /** `user.createdAt`: the virtual Favorites collection's timestamp and the head's floor. */
    val createdAt: Long,
    private val clock: FakeServerClock,
    private val ids: IdSource
) {
    internal val collections = LinkedHashMap<String, ServerCollection>()
    internal val bookmarks = LinkedHashMap<String, ServerBookmark>()
    internal val memberships = LinkedHashMap<String, ServerMembership>()
    internal val notes = LinkedHashMap<String, ServerNote>()
    internal val sessions = LinkedHashMap<String, ServerSession>()
    internal val readingBookmarks = LinkedHashMap<String, ServerReadingBookmark>()
    internal val logs = LinkedHashMap<String, LogRow>()
    private var lastStamp = createdAt

    /** The backend reading bookmarks will need: GET returns a slot's written `mushafId`, not its group's stand-in. */
    @Volatile var keepsMushafId = false

    /** Token scopes. GET silently skips resources without scope; POST rejects the whole batch with 403. */
    var scopes: Set<String> = ALL_SCOPES

    /** The sync head: `max(user.createdAt, lastMutationAt)`. */
    val head: Long get() = maxOf(createdAt, logs.values.maxOfOrNull { it.lastChangeAt } ?: createdAt)

    /** Wall time for stored client timestamps the request did not provide. */
    internal fun now(): Long = clock.now()

    internal fun newId(prefix: String): String = ids.next(prefix)

    /**
     * `ResourceLogSubscriber`: CREATE resets the row; UPDATE and DELETE stamp it. Time is monotonic
     * per user, so a clash is bumped by 1 ms.
     */
    internal fun log(resource: String, resourceId: String, kind: LogKind) {
        val now = clock.now()
        lastStamp = if (now <= lastStamp) lastStamp + 1 else now
        val stamp = lastStamp
        val key = "$resource:$resourceId"
        val existing = logs[key]
        logs[key] = when {
            existing == null -> when (kind) {
                LogKind.CREATE -> LogRow(resource, resourceId, stamp, seq = ids.seq())
                LogKind.UPDATE -> LogRow(resource, resourceId, stamp, updatedAt = stamp, seq = ids.seq())
                LogKind.DELETE -> LogRow(resource, resourceId, stamp, deletedAt = stamp, seq = ids.seq())
            }
            kind == LogKind.CREATE -> existing.copy(createdAt = stamp, updatedAt = null, deletedAt = null)
            kind == LogKind.UPDATE -> existing.copy(updatedAt = stamp)
            else -> existing.copy(deletedAt = stamp)
        }
    }

    internal fun logRow(resource: String, resourceId: String): LogRow? = logs["$resource:$resourceId"]

    internal fun snapshot(): Snapshot = Snapshot(
        LinkedHashMap(collections), LinkedHashMap(bookmarks), LinkedHashMap(memberships), LinkedHashMap(notes),
        LinkedHashMap(sessions), LinkedHashMap(readingBookmarks), LinkedHashMap(logs), lastStamp
    )

    internal fun restore(snapshot: Snapshot) {
        collections.replaceWith(snapshot.collections)
        bookmarks.replaceWith(snapshot.bookmarks)
        memberships.replaceWith(snapshot.memberships)
        notes.replaceWith(snapshot.notes)
        sessions.replaceWith(snapshot.sessions)
        readingBookmarks.replaceWith(snapshot.readingBookmarks)
        logs.replaceWith(snapshot.logs)
        lastStamp = snapshot.lastStamp
    }

    internal class Snapshot(
        val collections: Map<String, ServerCollection>,
        val bookmarks: Map<String, ServerBookmark>,
        val memberships: Map<String, ServerMembership>,
        val notes: Map<String, ServerNote>,
        val sessions: Map<String, ServerSession>,
        val readingBookmarks: Map<String, ServerReadingBookmark>,
        val logs: Map<String, LogRow>,
        val lastStamp: Long
    )

    companion object {
        const val DEFAULT_COLLECTION_ID = "__default__"
        val ALL_SCOPES = setOf("bookmark", "collection", "note", "reading_session")
    }
}

internal enum class LogKind { CREATE, UPDATE, DELETE }

private fun <K, V> LinkedHashMap<K, V>.replaceWith(source: Map<K, V>) {
    clear()
    putAll(source)
}

/** Server ids. They never contain '-', because composite membership ids are split on it. */
internal class IdSource {
    private var counter = 0L
    private var sequence = 0L

    @Synchronized
    fun next(prefix: String): String = "$prefix${++counter}"

    @Synchronized
    fun seq(): Long = ++sequence
}

/**
 * The server's clock: wall time plus [offsetMillis], or [frozenAt] when set. Logged timestamps are
 * also made monotonic per user, like the backend.
 */
class FakeServerClock {
    @Volatile var offsetMillis: Long = 0
    @Volatile var frozenAt: Long? = null

    fun now(): Long = frozenAt ?: (System.currentTimeMillis() + offsetMillis)

    fun advanceBy(millis: Long) {
        frozenAt?.let { frozenAt = it + millis } ?: run { offsetMillis += millis }
    }
}

/** JavaScript's `Date.prototype.toISOString`. */
internal fun isoMillis(epochMillis: Long): String = ISO.format(Instant.ofEpochMilli(epochMillis))

private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

package com.quran.shared.syncv2.types

import com.quran.shared.syncv2.Field
import com.quran.shared.syncv2.Change
import com.quran.shared.syncv2.OutgoingMutation
import com.quran.shared.syncv2.Refs
import com.quran.shared.syncv2.RemoteEntry
import com.quran.shared.syncv2.RemoteItem
import com.quran.shared.syncv2.State
import com.quran.shared.syncv2.SyncType
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.epochMillis
import com.quran.shared.syncv2.get
import com.quran.shared.syncv2.getOrNull
import com.quran.shared.syncv2.int
import com.quran.shared.syncv2.isoTime
import com.quran.shared.syncv2.stateOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ReadingSession(val localId: Long, val sura: Int, val ayah: Int, val updatedAt: Long)

/**
 * Plain CRUD over `reading_session` for this module's tests, standing in for the app's repository (persistence).
 * Mobile keeps the newest [MAX_SESSIONS]. The companion's [prune] is production API: the app's reading-session store
 * and [ReadingSessionsType.prune] both run it, so every local write and every sync ranks sessions the same way.
 */
class ReadingSessionsRepository(private val database: SyncV2Database) {
    private val queries get() = database.sessionsQueries

    fun sessions(): List<ReadingSession> = queries.all().executeAsList()
        .map { ReadingSession(it.local_id, it.sura.toInt(), it.ayah.toInt(), it.updated_at) }

    /** Records a visit: a revisit bumps the existing session. */
    fun addSession(sura: Int, ayah: Int, at: Long) = database.transaction {
        val existing = queries.at(sura.toLong(), ayah.toLong()).executeAsOneOrNull()
        if (existing == null) {
            queries.insert(sura.toLong(), ayah.toLong(), at, at)
        } else {
            queries.update(sura.toLong(), ayah.toLong(), at, existing.local_id)
        }
        prune(database)
    }

    /** Moves a session; a session already at the target is replaced (as the server does). */
    fun moveSession(localId: Long, sura: Int, ayah: Int, at: Long) = database.transaction {
        queries.at(sura.toLong(), ayah.toLong()).executeAsOneOrNull()
            ?.takeIf { it.local_id != localId }
            ?.let { queries.delete(it.local_id) }
        queries.update(sura.toLong(), ayah.toLong(), at, localId)
    }

    fun deleteSession(sura: Int, ayah: Int) {
        queries.at(sura.toLong(), ayah.toLong()).executeAsOneOrNull()?.let { queries.delete(it.local_id) }
    }

    companion object {
        const val MAX_SESSIONS = 20

        /**
         * Production API, used by the app's reading-session store as well as by the sync type: keeps the newest
         * [MAX_SESSIONS]. Every device ranks the same way: clientUpdatedAt descending, ties broken by verse.
         */
        fun prune(database: SyncV2Database) {
            database.sessionsQueries.all().executeAsList().drop(MAX_SESSIONS)
                .forEach { database.sessionsQueries.delete(it.local_id) }
        }
    }
}

/** READING_SESSION: natural key = verse; the server upserts CREATEs by verse. */
class ReadingSessionsType(private val database: SyncV2Database) : SyncType {
    private val queries get() = database.sessionsQueries
    override val name = "READING_SESSION"
    override val ignoredFields = setOf(CREATED_AT.name)
    /** A verse is one value: a local move and a remote move must never mix into a third verse. */
    override val mergeWholeObject = true

    override fun naturalKey(state: State) = "${state[SURA]}:${state[AYAH]}"
    override fun clock(state: State) = state[UPDATED_AT]

    override fun readAll(): Map<Long, State> = queries.all().executeAsList().associate {
        it.local_id to stateOf(SURA(it.sura.toInt()), AYAH(it.ayah.toInt()), UPDATED_AT(it.updated_at), CREATED_AT(it.created_at))
    }

    override fun insert(state: State, localId: Long?): Long {
        val sura = state[SURA].toLong()
        val ayah = state[AYAH].toLong()
        val createdAt = state.getOrNull(CREATED_AT) ?: clock(state)
        if (localId != null) {
            queries.insertWithId(localId, sura, ayah, createdAt, clock(state))
            return localId
        }
        queries.insert(sura, ayah, createdAt, clock(state))
        return queries.lastInsertId().executeAsOne()
    }

    override fun update(localId: Long, state: State) {
        queries.update(state[SURA].toLong(), state[AYAH].toLong(), clock(state), localId)
    }

    override fun park(localId: Long) {
        queries.park(localId)
    }
    override fun delete(localId: Long) {
        queries.delete(localId)
    }
    override fun prune(synced: Map<Long, State>) = ReadingSessionsRepository.prune(database)

    override fun fromRemote(entries: List<RemoteEntry>, refs: Refs): List<RemoteItem> =
        entries.filter { it.resource == name }.mapNotNull(::fromRemote)

    private fun fromRemote(entry: RemoteEntry): RemoteItem? {
        val id = entry.resourceId ?: return null
        if (entry.type == "DELETE") return RemoteItem.Deleted(id, entry.timestamp)
        val sura = entry.data.int("chapterNumber") ?: return null
        val ayah = entry.data.int("verseNumber") ?: return null
        val updatedAt = entry.data.epochMillis("clientUpdatedAt") ?: entry.timestamp
        val createdAt = entry.data.epochMillis("clientCreatedAt") ?: updatedAt
        return RemoteItem.Upsert(
            id,
            stateOf(SURA(sura), AYAH(ayah), UPDATED_AT(updatedAt), CREATED_AT(createdAt)),
            entry.timestamp
        )
    }

    override fun mutation(change: Change, refs: Refs) = when (change) {
        is Change.Create -> OutgoingMutation("CREATE", name, null, payload(change.state, create = true))
        is Change.Update -> OutgoingMutation("UPDATE", name, change.remoteId, payload(change.state, create = false))
        is Change.Delete -> OutgoingMutation("DELETE", name, change.remoteId, null)
    }

    override fun validate(change: Change): String? =
        if (change is Change.Delete || Verses.exists(change.state[SURA], change.state[AYAH])) null else "INVALID_VERSE"

    private fun payload(state: State, create: Boolean) = buildJsonObject {
        put("chapterNumber", state[SURA])
        put("verseNumber", state[AYAH])
        if (create) put("clientCreatedAt", isoTime(state.getOrNull(CREATED_AT) ?: clock(state)))
        put("clientUpdatedAt", isoTime(clock(state)))
    }

    private companion object {
        val SURA = Field.int("sura")
        val AYAH = Field.int("ayah")
        val UPDATED_AT = Field.long("updatedAt")
        val CREATED_AT = Field.long("createdAt")
    }
}

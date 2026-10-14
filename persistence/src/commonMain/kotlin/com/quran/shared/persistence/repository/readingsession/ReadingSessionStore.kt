package com.quran.shared.persistence.repository.readingsession

import com.quran.shared.syncv2.db.Reading_session
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.ReadingSessionsRepository

/**
 * The one write path for reading sessions, so every local write keeps the same rules: one session per verse, and only
 * the newest [ReadingSessionsRepository.MAX_SESSIONS] are kept, ranked the way every device ranks them (newest
 * `clientUpdatedAt` first, ties by verse). Callers run writes in a transaction.
 */
internal class ReadingSessionStore(private val database: SyncV2Database) {
    private val queries get() = database.sessionsQueries

    /** Newest first. */
    fun all(): List<Reading_session> = queries.all().executeAsList()

    fun get(localId: Long): Reading_session? = queries.get(localId).executeAsOneOrNull()

    fun at(sura: Int, ayah: Int): Reading_session? = queries.at(sura.toLong(), ayah.toLong()).executeAsOneOrNull()

    /** Inserts a session, or moves an existing one at the verse to [updatedAt]; then prunes. Returns its id. */
    fun visit(sura: Int, ayah: Int, createdAt: Long, updatedAt: Long = createdAt): Long {
        val existing = at(sura, ayah)
        val localId = if (existing == null) {
            queries.insert(sura.toLong(), ayah.toLong(), createdAt, updatedAt)
            queries.lastInsertId().executeAsOne()
        } else {
            queries.update(sura.toLong(), ayah.toLong(), updatedAt, existing.local_id)
            existing.local_id
        }
        prune()
        return localId
    }

    /**
     * Moves session [localId] to the verse. When another session is already there, that one is kept and moved to
     * [updatedAt] and this one deleted, so one session per verse remains. Returns the id of the session that stays.
     */
    fun move(localId: Long, sura: Int, ayah: Int, updatedAt: Long): Long {
        val target = at(sura, ayah)?.takeIf { it.local_id != localId }
        val kept = if (target == null) {
            queries.update(sura.toLong(), ayah.toLong(), updatedAt, localId)
            localId
        } else {
            queries.delete(localId)
            queries.update(sura.toLong(), ayah.toLong(), updatedAt, target.local_id)
            target.local_id
        }
        prune()
        return kept
    }

    fun delete(localId: Long) {
        queries.delete(localId)
    }

    fun prune() = ReadingSessionsRepository.prune(database)
}

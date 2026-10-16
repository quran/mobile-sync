package com.quran.shared.persistence.repository.readingsession.repository

import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.persistence.RecordNotFoundException
import com.quran.shared.persistence.RecordNotFoundException.RecordType
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.persistence.repository.readingsession.ReadingSessionStore
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.currentPlatformDateTime
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
import com.quran.shared.persistence.util.toPlatform
import com.quran.shared.syncv2.db.Reading_session
import com.quran.shared.syncv2.db.SyncV2Database
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.native.HiddenFromObjC
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
@HiddenFromObjC
class ReadingSessionsRepositoryImpl(
    private val database: SyncV2Database
) : ReadingSessionsRepository {

    private val logger = Logger.withTag("ReadingSessionsRepository")
    private val store = ReadingSessionStore(database)

    override suspend fun getReadingSessions(): List<ReadingSession> = withContext(Dispatchers.IO) {
        store.all().map { it.toReadingSession() }
    }

    override fun getReadingSessionsFlow(): Flow<List<ReadingSession>> =
        database.sessionsQueries.all().asFlow().map {
            withContext(Dispatchers.IO) { store.all().map { row -> row.toReadingSession() } }
        }

    override suspend fun addReadingSession(sura: Int, ayah: Int): ReadingSession =
        addReadingSession(sura, ayah, currentPlatformDateTime())

    override suspend fun addReadingSession(sura: Int, ayah: Int, timestamp: PlatformDateTime): ReadingSession {
        logger.i { "Adding reading session ($sura:$ayah)" }
        val at = timestamp.toEpochMillisecondsFromPlatform()
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val localId = store.visit(sura, ayah, at)
                // A visit older than the newest twenty is pruned at once; it is still the session that was added.
                store.get(localId)?.toReadingSession()
                    ?: ReadingSession(sura, ayah, Instant.fromEpochMilliseconds(at).toPlatform(), localId.toString())
            }
        }
    }

    override suspend fun updateReadingSession(id: String, sura: Int, ayah: Int): ReadingSession =
        updateReadingSession(id, sura, ayah, currentPlatformDateTime())

    override suspend fun updateReadingSession(
        id: String,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): ReadingSession {
        logger.i { "Updating reading session id=$id to $sura:$ayah" }
        val at = timestamp.toEpochMillisecondsFromPlatform()
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val existing = id.toLongOrNull()?.let(store::get)
                    ?: throw RecordNotFoundException(RecordType.READING_SESSION, id)
                val kept = store.move(existing.local_id, sura, ayah, at)
                requireNotNull(store.get(kept)) { "Expected active reading session id=$kept after update." }.toReadingSession()
            }
        }
    }

    override suspend fun deleteReadingSession(sura: Int, ayah: Int): Boolean {
        logger.i { "Deleting reading session for $sura:$ayah" }
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                val existing = store.at(sura, ayah) ?: return@transactionWithResult false
                store.delete(existing.local_id)
                true
            }
        }
    }
}

internal fun Reading_session.toReadingSession() = ReadingSession(
    sura = sura.toInt(),
    ayah = ayah.toInt(),
    lastUpdated = Instant.fromEpochMilliseconds(updated_at).toPlatform(),
    id = local_id.toString()
)

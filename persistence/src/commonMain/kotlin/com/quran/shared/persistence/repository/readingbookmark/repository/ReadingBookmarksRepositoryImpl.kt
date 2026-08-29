package com.quran.shared.persistence.repository.readingbookmark.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LOCAL_MUTATION_ENTITY_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.LocalSyncReadingBookmark
import com.quran.shared.persistence.input.RemoteReadingBookmark
import com.quran.shared.persistence.model.DatabaseReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard
import com.quran.shared.persistence.repository.buildRemoteResourceExistenceMap
import com.quran.shared.persistence.repository.readingbookmark.extension.toReadingBookmark
import com.quran.shared.persistence.repository.readingbookmark.extension.toReadingBookmarkMutation
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.currentPlatformDateTime
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
import com.quran.shared.persistence.util.toPlatform
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
class ReadingBookmarksRepositoryImpl(
    private val database: QuranDatabase
) : ReadingBookmarksRepository, ReadingBookmarksSynchronizationRepository {

    private val queries = lazy { database.reading_bookmarksQueries }

    override suspend fun getReadingBookmarks(): List<ReadingBookmark> =
        withContext(Dispatchers.IO) {
            queries.value.getReadingBookmarks().executeAsList().map(DatabaseReadingBookmark::toReadingBookmark)
        }

    override fun getReadingBookmarksFlow(): Flow<List<ReadingBookmark>> =
        queries.value.getReadingBookmarks()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map(DatabaseReadingBookmark::toReadingBookmark) }

    override suspend fun setAyahReadingBookmark(
        slot: Int,
        sura: Int,
        ayah: Int
    ): ReadingBookmark = setAyahReadingBookmark(slot, sura, ayah, currentPlatformDateTime())

    override suspend fun setAyahReadingBookmark(
        slot: Int,
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): ReadingBookmark = withContext(Dispatchers.IO) {
        requireValidSlot(slot)
        val timestampMillis = timestamp.toEpochMillisecondsFromPlatform()
        queries.value.setAyahReadingBookmark(
            slot = slot.toLong(),
            sura = sura.toLong(),
            ayah = ayah.toLong(),
            mushaf_id = SUPPORTED_MUSHAF_ID,
            timestamp = timestampMillis
        )
        requireNotNull(queries.value.getReadingBookmarkForSlot(slot.toLong()).executeAsOneOrNull())
            .toReadingBookmark()
    }

    override suspend fun setPageReadingBookmark(slot: Int, page: Int): ReadingBookmark =
        setPageReadingBookmark(slot, page, currentPlatformDateTime())

    override suspend fun setPageReadingBookmark(
        slot: Int,
        page: Int,
        timestamp: PlatformDateTime
    ): ReadingBookmark = withContext(Dispatchers.IO) {
        requireValidSlot(slot)
        val timestampMillis = timestamp.toEpochMillisecondsFromPlatform()
        queries.value.setPageReadingBookmark(
            slot = slot.toLong(),
            page = page.toLong(),
            mushaf_id = SUPPORTED_MUSHAF_ID,
            timestamp = timestampMillis
        )
        requireNotNull(queries.value.getReadingBookmarkForSlot(slot.toLong()).executeAsOneOrNull())
            .toReadingBookmark()
    }

    override suspend fun renameReadingBookmark(slot: Int, name: String?): ReadingBookmark =
        renameReadingBookmark(slot, name, currentPlatformDateTime())

    override suspend fun renameReadingBookmark(
        slot: Int,
        name: String?,
        timestamp: PlatformDateTime
    ): ReadingBookmark =
        withContext(Dispatchers.IO) {
            requireValidSlot(slot)
            queries.value.renameReadingBookmark(
                slot = slot.toLong(),
                name = name,
                timestamp = timestamp.toEpochMillisecondsFromPlatform()
            )
            requireNotNull(queries.value.getReadingBookmarkForSlot(slot.toLong()).executeAsOneOrNull())
                .toReadingBookmark()
        }

    override suspend fun clearReadingBookmark(slot: Int): ReadingBookmark =
        clearReadingBookmark(slot, currentPlatformDateTime())

    override suspend fun clearReadingBookmark(slot: Int, timestamp: PlatformDateTime): ReadingBookmark =
        withContext(Dispatchers.IO) {
            requireValidSlot(slot)
            queries.value.clearReadingBookmark(
                slot = slot.toLong(),
                timestamp = timestamp.toEpochMillisecondsFromPlatform()
            )
            requireNotNull(queries.value.getReadingBookmarkForSlot(slot.toLong()).executeAsOneOrNull())
                .toReadingBookmark()
        }

    override suspend fun fetchMutatedReadingBookmarks(): List<LocalModelMutation<LocalSyncReadingBookmark>> =
        withContext(Dispatchers.IO) {
            queries.value.getUnsyncedReadingBookmarks().executeAsList().map { it.toReadingBookmarkMutation() }
        }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteReadingBookmark>>,
        localMutationsToClear: List<LocalModelMutation<LocalSyncReadingBookmark>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) = withContext(Dispatchers.IO) {
        writeBoundaryGuard.checkWriteBoundary()
        database.transaction {
            updatesToPersist.forEach { remote ->
                if (remote.ack == null) {
                    persistRemote(remote)
                } else {
                    acknowledgeRemoteMutation(remote)
                }
            }
            localMutationsToClear.forEach(::acknowledgeLocalMutation)
        }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        buildRemoteResourceExistenceMap(remoteIDs) { chunk ->
            queries.value.checkRemoteIDsExistence(chunk).executeAsList().mapNotNull { it.remote_id }
        }

    override suspend fun fetchReadingBookmarkByRemoteId(remoteId: String): RemoteReadingBookmark? =
        withContext(Dispatchers.IO) {
            queries.value.getReadingBookmarkByRemoteId(remoteId).executeAsOneOrNull()?.toRemoteInput()
        }

    private fun persistRemote(remote: RemoteModelMutation<RemoteReadingBookmark>) {
        val model = remote.model
        requireValidSlot(model.slot)
        val updatedAt = model.lastUpdated.fromPlatform().toEpochMilliseconds()
        val createdAt = model.createdAt?.fromPlatform()?.toEpochMilliseconds() ?: updatedAt
        queries.value.persistRemoteReadingBookmark(
            remote_id = remote.remoteID,
            slot = model.slot.toLong(),
            name = model.name,
            bookmark_type = model.type,
            sura = model.sura?.toLong(),
            ayah = model.ayah?.toLong(),
            page = model.page?.toLong(),
            mushaf_id = model.type?.let { SUPPORTED_MUSHAF_ID },
            created_at = createdAt,
            modified_at = updatedAt
        )
    }

    private fun acknowledgeLocalMutation(local: LocalModelMutation<LocalSyncReadingBookmark>) {
        val ack = local.ack ?: return
        if (ack.resource != LocalMutationResource.READING_BOOKMARK ||
            ack.facet != LOCAL_MUTATION_ENTITY_FACET ||
            ack.localID != local.localID
        ) {
            return
        }
        val remoteId = local.remoteID ?: return
        queries.value.acknowledgeReadingBookmarkMutation(
            local_id = local.localID.toLong(),
            remote_id = remoteId,
            pending_op = ack.observedPendingOp.name,
            pending_version = ack.observedPendingVersion,
            modified_at = local.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        )
    }

    private fun acknowledgeRemoteMutation(remote: RemoteModelMutation<RemoteReadingBookmark>) {
        val ack = remote.ack ?: return
        if (ack.resource != LocalMutationResource.READING_BOOKMARK ||
            ack.facet != LOCAL_MUTATION_ENTITY_FACET
        ) {
            return
        }
        queries.value.acknowledgeReadingBookmarkMutation(
            local_id = ack.localID.toLong(),
            remote_id = remote.remoteID,
            pending_op = ack.observedPendingOp.name,
            pending_version = ack.observedPendingVersion,
            modified_at = remote.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        )
    }

    private fun DatabaseReadingBookmark.toRemoteInput(): RemoteReadingBookmark =
        RemoteReadingBookmark(
            slot = slot.toInt(),
            name = name,
            type = bookmark_type,
            sura = sura?.toInt(),
            ayah = ayah?.toInt(),
            page = page?.toInt(),
            lastUpdated = Instant.fromEpochMilliseconds(modified_at).toPlatform(),
            createdAt = Instant.fromEpochMilliseconds(created_at).toPlatform()
        )

    private fun requireValidSlot(slot: Int) {
        require(slot in 1..3) { "Reading bookmark slot must be between 1 and 3: $slot" }
    }

    private companion object {
        const val SUPPORTED_MUSHAF_ID = 1L
    }
}

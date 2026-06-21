package com.quran.shared.persistence.repository.collection.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.touchlab.kermit.Logger
import com.quran.shared.di.AppScope
import com.quran.shared.mutations.LOCAL_MUTATION_ENTITY_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.model.Collection
import com.quran.shared.persistence.model.DatabaseCollection
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard
import com.quran.shared.persistence.repository.buildRemoteResourceExistenceMap
import com.quran.shared.persistence.repository.bookmark.BookmarkDependencyReconciler
import com.quran.shared.persistence.repository.collection.extension.toCollection
import com.quran.shared.persistence.repository.collection.extension.toCollectionMutation
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toEpochMillisecondsOrNull
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
class CollectionsRepositoryImpl(
    private val database: QuranDatabase,
    private val reconciler: BookmarkDependencyReconciler = BookmarkDependencyReconciler(database)
) : CollectionsRepository, CollectionsSynchronizationRepository {

    private val logger = Logger.withTag("CollectionsRepository")
    private val collectionQueries = lazy { database.collectionsQueries }
    private val bookmarkCollectionQueries = lazy { database.bookmark_collectionsQueries }

    override suspend fun getAllCollections(): List<Collection> {
        return withContext(Dispatchers.IO) {
            collectionQueries.value.getCollections()
                .executeAsList()
                .map { it.toCollection() }
        }
    }

    override fun getCollectionsFlow(): Flow<List<Collection>> {
        return collectionQueries.value.getCollections()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { it.toCollection() }
            }
    }

    override suspend fun addCollection(name: String): Collection {
        return addCollectionWithTimestampMillis(name, timestampMillis = null)
    }

    override suspend fun addCollection(name: String, timestamp: PlatformDateTime): Collection {
        return addCollectionWithTimestampMillis(name, timestamp.toEpochMillisecondsOrNull())
    }

    private suspend fun addCollectionWithTimestampMillis(name: String, timestampMillis: Long?): Collection {
        logger.i { "Adding collection with name=$name" }
        return withContext(Dispatchers.IO) {
            collectionQueries.value.addNewCollection(
                name = name,
                timestamp = timestampMillis
            )
            val record = collectionQueries.value.getCollectionByName(name)
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected collection for name=$name after insert." }
            record.toCollection()
        }
    }

    override suspend fun updateCollection(localId: String, name: String): Collection {
        return updateCollectionWithTimestampMillis(localId, name, timestampMillis = null)
    }

    override suspend fun updateCollection(localId: String, name: String, timestamp: PlatformDateTime): Collection {
        return updateCollectionWithTimestampMillis(localId, name, timestamp.toEpochMillisecondsOrNull())
    }

    private suspend fun updateCollectionWithTimestampMillis(
        localId: String,
        name: String,
        timestampMillis: Long?
    ): Collection {
        logger.i { "Updating collection localId=$localId with name=$name" }
        return withContext(Dispatchers.IO) {
            val id = localId.toLong()
            var updatedCollection: Collection? = null

            database.transaction {
                val existing = collectionQueries.value.getCollectionByLocalId(id)
                    .executeAsOneOrNull()
                require(existing?.deleted == 0L) {
                    "Expected active collection localId=$localId before update."
                }

                collectionQueries.value.updateCollectionName(
                    name = name,
                    id = id,
                    timestamp = timestampMillis
                )
                val record = requireNotNull(
                    collectionQueries.value.getCollectionByLocalId(id)
                        .executeAsOneOrNull()
                ) { "Expected collection localId=$localId after update." }
                require(record.deleted == 0L) { "Expected active collection localId=$localId after update." }
                updatedCollection = record.toCollection()
            }

            requireNotNull(updatedCollection)
        }
    }

    override suspend fun deleteCollection(localId: String): Boolean {
        logger.i { "Deleting collection localId=$localId" }
        withContext(Dispatchers.IO) {
            database.transaction {
                collectionQueries.value.deleteCollection(
                    id = localId.toLong()
                )
                reconciler.reconcile()
            }
        }
        return true
    }

    override suspend fun fetchMutatedCollections(): List<LocalModelMutation<Collection>> {
        return withContext(Dispatchers.IO) {
            collectionQueries.value.getUnsyncedCollections()
                .executeAsList()
                .map { it.toCollectionMutation() }
        }
    }

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteCollection>>,
        localMutationsToClear: List<LocalModelMutation<Collection>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) {
        logger.i {
            "Applying collection remote changes with ${updatesToPersist.size} updates " +
                    "and clearing ${localMutationsToClear.size} local mutations"
        }
        return withContext(Dispatchers.IO) {
            writeBoundaryGuard.checkWriteBoundary()
            database.transaction {
                localMutationsToClear.forEach { local ->
                    if (local.mutation != Mutation.CREATED && ackMatchesCurrentRow(local)) {
                        clearLocalMutation(local)
                    }
                }

                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED, Mutation.MODIFIED -> applyRemoteCollectionUpsert(remote)
                        Mutation.DELETED -> applyRemoteCollectionDeletion(remote)
                    }
                }
                reconciler.reconcile()
            }
        }
    }

    private fun applyRemoteCollectionUpsert(remote: RemoteModelMutation<RemoteCollection>) {
        val name = remote.model.name
        if (name.isNullOrEmpty()) {
            logger.w { "Skipping remote collection mutation without name: remoteId=${remote.remoteID}" }
            return
        }

        val updatedAt = remote.model.lastUpdated.fromPlatform().toEpochMilliseconds()
        val existingByRemote = collectionQueries.value.getCollectionByRemoteId(remote.remoteID)
            .executeAsOneOrNull()

        if (existingByRemote != null) {
            if (existingByRemote.hasPendingLocalMutation()) {
                logger.i { "Skipping remote collection upsert for pending local row: remoteId=${remote.remoteID}" }
                return
            }
            collectionQueries.value.updateRemoteCollection(
                remote_id = remote.remoteID,
                name = name,
                modified_at = updatedAt
            )
            return
        }

        if (remote.mutation == Mutation.CREATED) {
            val createdAck = remote.createdAckOrNull()
            if (createdAck != null && attachRemoteIdForCreatedAck(remote, createdAck, updatedAt)) {
                return
            }
        }

        if (remote.mutation == Mutation.CREATED) {
            if (attachRemoteIdForSemanticReplay(remote, name, updatedAt)) {
                return
            }
        }

        val existingByName = collectionQueries.value.getCollectionByName(name)
            .executeAsOneOrNull()
        if (existingByName != null) {
            logger.i {
                "Skipping remote collection upsert with colliding name: " +
                    "remoteId=${remote.remoteID}, localId=${existingByName.local_id}"
            }
        } else {
            collectionQueries.value.persistRemoteCollection(
                remote_id = remote.remoteID,
                name = name,
                created_at = updatedAt,
                modified_at = updatedAt
            )
        }
    }

    private fun applyRemoteCollectionDeletion(remote: RemoteModelMutation<RemoteCollection>) {
        val existing = collectionQueries.value.getCollectionByRemoteId(remote.remoteID)
            .executeAsOneOrNull()
        if (existing?.hasPendingLocalMutation() == true) {
            logger.i { "Skipping remote collection deletion for pending local row: remoteId=${remote.remoteID}" }
            return
        }
        val affectedBookmarkLocalIds = bookmarkCollectionQueries.value
            .getBookmarkLocalIdsForCollectionRemoteId(remote.remoteID)
            .executeAsList()
        collectionQueries.value.deleteRemoteCollection(remote_id = remote.remoteID)
        affectedBookmarkLocalIds.forEach { bookmarkLocalId ->
            reconciler.pruneBookmarkIfOrphan(bookmarkLocalId)
        }
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        return buildRemoteResourceExistenceMap(remoteIDs) { chunk ->
            collectionQueries.value.checkRemoteIDsExistence(chunk)
                .executeAsList()
                .mapNotNull { it.remote_id }
        }
    }

    private fun attachRemoteIdForCreatedAck(
        remote: RemoteModelMutation<RemoteCollection>,
        ack: CreatedCollectionAck,
        updatedAt: Long
    ): Boolean {
        val row = collectionQueries.value.getCollectionByLocalId(ack.localId)
            .executeAsOneOrNull()
        if (row?.remote_id != null) {
            return false
        }
        collectionQueries.value.attachRemoteCollectionIdForCreatedAck(
            local_id = ack.localId,
            remote_id = remote.remoteID,
            pending_version = ack.pendingVersion,
            modified_at = updatedAt
        )
        val attached = collectionQueries.value.getCollectionByLocalId(ack.localId)
            .executeAsOneOrNull()
        return attached?.remote_id == remote.remoteID
    }

    private fun attachRemoteIdForSemanticReplay(
        remote: RemoteModelMutation<RemoteCollection>,
        name: String,
        updatedAt: Long
    ): Boolean {
        val deletedCandidates = collectionQueries.value.getPendingDeletedCreatedCollectionsByName(name)
            .executeAsList()
        if (deletedCandidates.size > 1) {
            throw IllegalStateException(
                "Ambiguous deleted collection semantic replay candidates for remoteId=${remote.remoteID}"
            )
        }
        if (deletedCandidates.size == 1) {
            return attachRemoteIdForSemanticReplay(remote, deletedCandidates.single(), updatedAt)
        }

        val activeCandidates = collectionQueries.value.getPendingActiveCreatedCollectionsByName(name)
            .executeAsList()
        if (activeCandidates.size != 1) {
            return false
        }
        return attachRemoteIdForSemanticReplay(remote, activeCandidates.single(), updatedAt)
    }

    private fun attachRemoteIdForSemanticReplay(
        remote: RemoteModelMutation<RemoteCollection>,
        row: DatabaseCollection,
        updatedAt: Long
    ): Boolean {
        if (row.remote_id != null || row.pendingMutation() != Mutation.CREATED) {
            return false
        }
        collectionQueries.value.attachRemoteCollectionIdForSemanticReplay(
            local_id = row.local_id,
            remote_id = remote.remoteID,
            modified_at = updatedAt
        )
        val attached = collectionQueries.value.getCollectionByLocalId(row.local_id)
            .executeAsOneOrNull()
        return attached?.remote_id == remote.remoteID
    }

    private fun clearLocalMutation(local: LocalModelMutation<Collection>) {
        val ack = local.ack ?: return
        val affectedBookmarkLocalIds = if (local.mutation == Mutation.DELETED && local.remoteID != null) {
            bookmarkCollectionQueries.value
                .getBookmarkLocalIdsForCollectionRemoteId(local.remoteID)
                .executeAsList()
        } else {
            emptyList()
        }
        collectionQueries.value.clearLocalMutationFor(
            id = local.localID.toLong(),
            pending_version = ack.observedPendingVersion,
            pending_op = ack.observedPendingOp.name
        )
        affectedBookmarkLocalIds.forEach { bookmarkLocalId ->
            reconciler.pruneBookmarkIfOrphan(bookmarkLocalId)
        }
    }

    private fun ackMatchesCurrentRow(local: LocalModelMutation<Collection>): Boolean {
        val ack = local.ack ?: return false
        if (ack.localID != local.localID ||
            ack.resource != LocalMutationResource.COLLECTION ||
            ack.facet != LOCAL_MUTATION_ENTITY_FACET ||
            ack.observedPendingOp != local.mutation) {
            return false
        }
        val row = collectionQueries.value.getCollectionByLocalId(local.localID.toLong())
            .executeAsOneOrNull()
            ?: return false
        return row.pending_version == ack.observedPendingVersion &&
            row.pendingMutation() == ack.observedPendingOp
    }

    private fun DatabaseCollection.pendingMutation(): Mutation? {
        return when {
            remote_id == null -> Mutation.CREATED
            deleted == 1L -> Mutation.DELETED
            is_edited == 1L -> Mutation.MODIFIED
            else -> null
        }
    }

    private fun RemoteModelMutation<RemoteCollection>.createdAckOrNull(): CreatedCollectionAck? {
        val ack = ack ?: return null
        if (mutation != Mutation.CREATED ||
            ack.resource != LocalMutationResource.COLLECTION ||
            ack.facet != LOCAL_MUTATION_ENTITY_FACET ||
            ack.observedPendingOp != Mutation.CREATED) {
            return null
        }
        return CreatedCollectionAck(
            localId = ack.localID.toLong(),
            pendingVersion = ack.observedPendingVersion
        )
    }

    private fun DatabaseCollection.hasPendingLocalMutation(): Boolean = pendingMutation() != null

    private data class CreatedCollectionAck(
        val localId: Long,
        val pendingVersion: Long
    )
}

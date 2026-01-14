package com.quran.shared.persistence.repository.collection.repository

import co.touchlab.kermit.Logger
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.model.Collection
import com.quran.shared.persistence.repository.collection.extension.toCollection
import com.quran.shared.persistence.repository.collection.extension.toCollectionMutation
import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS
import com.quran.shared.persistence.util.fromPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class CollectionsRepositoryImpl(
    private val database: QuranDatabase
) : CollectionsRepository, CollectionsSynchronizationRepository {

    private val logger = Logger.withTag("CollectionsRepository")
    private val collectionQueries = lazy { database.collectionsQueries }

    override suspend fun getAllCollections(): List<Collection> {
        return withContext(Dispatchers.IO) {
            collectionQueries.value.getCollections()
                .executeAsList()
                .map { it.toCollection() }
        }
    }

    override suspend fun addCollection(name: String): Collection {
        logger.i { "Adding collection with name=$name" }
        return withContext(Dispatchers.IO) {
            collectionQueries.value.addNewCollection(name)
            val record = collectionQueries.value.getCollectionByName(name)
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected collection for name=$name after insert." }
            record.toCollection()
        }
    }

    override suspend fun updateCollection(localId: String, name: String): Collection {
        logger.i { "Updating collection localId=$localId with name=$name" }
        return withContext(Dispatchers.IO) {
            collectionQueries.value.updateCollectionName(name = name, id = localId.toLong())
            val record = collectionQueries.value.getCollectionByLocalId(localId.toLong())
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected collection localId=$localId after update." }
            record.toCollection()
        }
    }

    override suspend fun deleteCollection(localId: String): Boolean {
        logger.i { "Deleting collection localId=$localId" }
        withContext(Dispatchers.IO) {
            collectionQueries.value.deleteCollection(id = localId.toLong())
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
        localMutationsToClear: List<LocalModelMutation<Collection>>
    ) {
        logger.i {
            "Applying collection remote changes with ${updatesToPersist.size} updates " +
                "and clearing ${localMutationsToClear.size} local mutations"
        }
        return withContext(Dispatchers.IO) {
            database.transaction {
                localMutationsToClear.forEach { local ->
                    collectionQueries.value.clearLocalMutationFor(id = local.localID.toLong())
                }

                updatesToPersist.forEach { remote ->
                    when (remote.mutation) {
                        Mutation.CREATED, Mutation.MODIFIED -> applyRemoteCollectionUpsert(remote)
                        Mutation.DELETED -> applyRemoteCollectionDeletion(remote)
                    }
                }
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
            collectionQueries.value.updateRemoteCollection(
                remote_id = remote.remoteID,
                name = name,
                modified_at = updatedAt
            )
            return
        }

        val existingByName = collectionQueries.value.getCollectionByName(name)
            .executeAsOneOrNull()
        if (existingByName != null) {
            collectionQueries.value.updateRemoteCollectionByLocalId(
                local_id = existingByName.local_id,
                remote_id = remote.remoteID,
                name = name,
                modified_at = updatedAt
            )
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
        collectionQueries.value.deleteRemoteCollection(remote_id = remote.remoteID)
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> {
        if (remoteIDs.isEmpty()) {
            return emptyMap()
        }

        return withContext(Dispatchers.IO) {
            val existentIDs = mutableSetOf<String>()
            remoteIDs.chunked(SQLITE_MAX_BIND_PARAMETERS).forEach { chunk ->
                existentIDs.addAll(
                    collectionQueries.value.checkRemoteIDsExistence(chunk)
                        .executeAsList()
                        .mapNotNull { it.remote_id }
                )
            }

            remoteIDs.associateWith { existentIDs.contains(it) }
        }
    }
}

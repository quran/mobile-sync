@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class CollectionsRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: CollectionsRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        repository = CollectionsRepositoryImpl(database)
    }

    @Test
    fun `addCollection respects explicit timestamp`() = runTest {
        val collection = repository.addCollection("Favorites", timestamp(1234L))
        val record = database.collectionsQueries.getCollectionByLocalId(collection.localId.toLong()).executeAsOne()

        assertEquals(1234L, collection.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1234L, record.created_at)
        assertEquals(1234L, record.modified_at)
    }

    @Test
    fun `addCollection advances pending version for fresh create`() = runTest {
        val collection = repository.addCollection("Favorites", timestamp(1234L))
        val record = database.collectionsQueries.getCollectionByLocalId(collection.localId.toLong()).executeAsOne()
        val mutation = repository.fetchMutatedCollections().single()

        assertEquals(1L, record.pending_version)
        assertEquals(1L, mutation.ack?.observedPendingVersion)
        assertEquals(Mutation.CREATED, mutation.mutation)
    }

    @Test
    fun `updateCollection respects explicit timestamp and preserves created_at`() = runTest {
        val collection = repository.addCollection("Favorites", timestamp(1000L))

        val updated = repository.updateCollection(collection.localId, "Updated", timestamp(2345L))
        val record = database.collectionsQueries.getCollectionByLocalId(collection.localId.toLong()).executeAsOne()

        assertEquals(2345L, updated.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1000L, record.created_at)
        assertEquals(2345L, record.modified_at)
    }

    @Test
    fun `deleteCollection preserves timestamp for remote rows`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-collection-id",
            name = "Favorites",
            created_at = 1000L,
            modified_at = 1000L
        )
        val collection = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()

        repository.deleteCollection(collection.local_id.toString())

        val mutation = repository.fetchMutatedCollections().single()
        val record = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        assertEquals(Mutation.DELETED, mutation.mutation)
        assertEquals(1000L, mutation.model.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1000L, record.modified_at)
    }

    @Test
    fun `applyRemoteChanges clears collection ACK when pending version still matches`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-collection-id",
            name = "Favorites",
            created_at = 1000L,
            modified_at = 1000L
        )
        val collection = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        repository.updateCollection(collection.local_id.toString(), "Synced", timestamp(2000L))
        val mutation = repository.fetchMutatedCollections().single()

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("Synced", timestamp(2000L)),
                    remoteID = "remote-collection-id",
                    mutation = Mutation.MODIFIED
                )
            ),
            localMutationsToClear = listOf(mutation)
        )

        val record = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        assertEquals("Synced", record.name)
        assertEquals(0L, record.is_edited)
        assertEquals(emptyList(), repository.fetchMutatedCollections())
    }

    @Test
    fun `applyRemoteChanges checks write boundary before collection transaction`() = runTest {
        assertFailsWith<IllegalStateException> {
            repository.applyRemoteChanges(
                updatesToPersist = listOf(
                    RemoteModelMutation(
                        model = RemoteCollection("Favorites", timestamp(2000L)),
                        remoteID = "remote-collection-id",
                        mutation = Mutation.CREATED
                    )
                ),
                localMutationsToClear = emptyList(),
                writeBoundaryGuard = PersistenceWriteBoundaryGuard {
                    throw IllegalStateException("stale epoch")
                }
            )
        }

        assertNull(database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOneOrNull())
    }

    @Test
    fun `applyRemoteChanges does not clear stale collection ACK after newer local write`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-collection-id",
            name = "Favorites",
            created_at = 1000L,
            modified_at = 1000L
        )
        val collection = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        repository.updateCollection(collection.local_id.toString(), "Uploaded", timestamp(2000L))
        val staleMutation = repository.fetchMutatedCollections().single()
        repository.updateCollection(collection.local_id.toString(), "Newer", timestamp(3000L))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("Uploaded", timestamp(2000L)),
                    remoteID = "remote-collection-id",
                    mutation = Mutation.MODIFIED
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        val remaining = repository.fetchMutatedCollections().single()
        assertEquals("Newer", record.name)
        assertEquals(1L, record.is_edited)
        assertEquals(Mutation.MODIFIED, remaining.mutation)
    }

    @Test
    fun `stale created collection ACK binds remote id and leaves newer rename pending`() = runTest {
        val collection = repository.addCollection("Favorites", timestamp(1000L))
        val staleMutation = repository.fetchMutatedCollections().single()
        repository.updateCollection(collection.localId, "Renamed", timestamp(2000L))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("Favorites", timestamp(1000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.collectionsQueries.getCollectionByLocalId(collection.localId.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedCollections().single()
        assertEquals("remote-created-collection-id", record.remote_id)
        assertEquals("Renamed", record.name)
        assertEquals(1L, record.is_edited)
        assertEquals(collection.localId, remaining.localID)
        assertEquals("remote-created-collection-id", remaining.remoteID)
        assertEquals(Mutation.MODIFIED, remaining.mutation)
    }

    @Test
    fun `stale created collection ACK binds remote id and leaves delete pending`() = runTest {
        val collection = repository.addCollection("Favorites", timestamp(1000L))
        val staleMutation = repository.fetchMutatedCollections().single()
        repository.deleteCollection(collection.localId)
        assertEquals(emptyList(), repository.getAllCollections())
        assertEquals(emptyList(), repository.fetchMutatedCollections())

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("Favorites", timestamp(1000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.collectionsQueries.getCollectionByLocalId(collection.localId.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedCollections().single()
        assertEquals(emptyList(), repository.getAllCollections())
        assertEquals("remote-created-collection-id", record.remote_id)
        assertEquals(1L, record.deleted)
        assertEquals(collection.localId, remaining.localID)
        assertEquals("remote-created-collection-id", remaining.remoteID)
        assertEquals(Mutation.DELETED, remaining.mutation)
    }

    @Test
    fun `deleteExisting import keeps local-created collection tombstone until create ACK binds`() = runTest {
        val collection = repository.addCollection("Favorites", timestamp(1000L))
        val staleMutation = repository.fetchMutatedCollections().single()
        PersistenceImportRepositoryImpl(database).importData(
            data = PersistenceImportData(
                collections = listOf(
                    ImportCollection(
                        importId = "imported-favorites",
                        name = "Favorites",
                        lastUpdated = timestamp(2000L)
                    )
                )
            ),
            deleteExisting = true
        )

        val tombstone = database.collectionsQueries.getCollectionByLocalId(collection.localId.toLong()).executeAsOne()
        val imported = database.collectionsQueries.getCollectionByName("Favorites").executeAsOne()
        assertEquals(null, tombstone.remote_id)
        assertEquals(1L, tombstone.deleted)
        assertEquals(collection.localId.toLong(), tombstone.local_id)
        assertEquals(1, repository.getAllCollections().size)
        assertEquals(1, repository.fetchMutatedCollections().size)
        assertEquals("Favorites", imported.name)
        assertEquals(0L, imported.deleted)
        assertEquals(null, imported.remote_id)
        assertEquals(2000L, imported.modified_at)

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("Favorites", timestamp(1000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.collectionsQueries.getCollectionByLocalId(collection.localId.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedCollections()
        val tombstoneDelete = remaining.single { it.localID == collection.localId }
        val importedCreate = remaining.single { it.localID != collection.localId }
        assertEquals("remote-created-collection-id", record.remote_id)
        assertEquals(1L, record.deleted)
        assertEquals("remote-created-collection-id", tombstoneDelete.remoteID)
        assertEquals(Mutation.DELETED, tombstoneDelete.mutation)
        assertEquals(null, importedCreate.remoteID)
        assertEquals(Mutation.CREATED, importedCreate.mutation)
    }

    @Test
    fun `ACKed collection delete removes tombstone without reactivating colliding active name`() = runTest {
        val collection = repository.addCollection("Favorites", timestamp(1000L))
        val staleCreate = repository.fetchMutatedCollections().single()
        PersistenceImportRepositoryImpl(database).importData(
            data = PersistenceImportData(
                collections = listOf(
                    ImportCollection(
                        importId = "replacement-favorites",
                        name = "Favorites",
                        lastUpdated = timestamp(2000L)
                    )
                )
            ),
            deleteExisting = true
        )
        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("Favorites", timestamp(1000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED,
                    ack = staleCreate.ack
                )
            ),
            localMutationsToClear = listOf(staleCreate)
        )
        val staleDelete = repository.fetchMutatedCollections()
            .single { it.localID == collection.localId }

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(null, timestamp(3000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = listOf(staleDelete)
        )

        val active = repository.getAllCollections().single()
        val remainingMutation = repository.fetchMutatedCollections().single()
        assertNull(database.collectionsQueries.getCollectionByLocalId(collection.localId.toLong()).executeAsOneOrNull())
        assertEquals("Favorites", active.name)
        assertEquals(Mutation.CREATED, remainingMutation.mutation)
        assertEquals(active.localId, remainingMutation.localID)
    }

    @Test
    fun `remote created collection without ACK does not move existing remote id by name`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-collection-1",
            name = "Favorites",
            created_at = 1000L,
            modified_at = 1000L
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("Favorites", timestamp(2000L)),
                    remoteID = "remote-collection-2",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val original = database.collectionsQueries.getCollectionByRemoteId("remote-collection-1").executeAsOne()
        assertEquals("Favorites", original.name)
        assertNull(database.collectionsQueries.getCollectionByRemoteId("remote-collection-2").executeAsOneOrNull())
    }

    @Test
    fun `remote created collection without ACK does not bind stale planned local create by name`() = runTest {
        val collection = repository.addCollection("Favorites", timestamp(1000L))
        val staleMutation = repository.fetchMutatedCollections().single()
        repository.updateCollection(collection.localId, "Renamed", timestamp(2000L))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("Favorites", timestamp(1000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val localRecord = database.collectionsQueries.getCollectionByLocalId(collection.localId.toLong()).executeAsOne()
        val remoteRecord = database.collectionsQueries.getCollectionByRemoteId("remote-created-collection-id")
            .executeAsOne()
        val remaining = repository.fetchMutatedCollections().single()
        assertEquals(null, localRecord.remote_id)
        assertEquals("Renamed", localRecord.name)
        assertEquals("Favorites", remoteRecord.name)
        assertEquals(collection.localId, remaining.localID)
        assertEquals(Mutation.CREATED, remaining.mutation)
    }

    @Test
    fun `remote created collection without ACK binds unique current pending create by name`() = runTest {
        val collection = repository.addCollection("Favorites", timestamp(1000L))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("Favorites", timestamp(2000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val record = database.collectionsQueries.getCollectionByLocalId(collection.localId.toLong()).executeAsOne()
        assertEquals("remote-created-collection-id", record.remote_id)
        assertEquals(0L, record.is_edited)
        assertEquals(1, repository.getAllCollections().size)
        assertEquals(emptyList(), repository.fetchMutatedCollections())
    }

    @Test
    fun `remote created collection without ACK binds deleted pending create by name and leaves delete pending`() = runTest {
        val collection = repository.addCollection("Favorites", timestamp(1000L))
        repository.deleteCollection(collection.localId)
        assertEquals(emptyList(), repository.getAllCollections())
        assertEquals(emptyList(), repository.fetchMutatedCollections())

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("Favorites", timestamp(2000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val record = database.collectionsQueries.getCollectionByLocalId(collection.localId.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedCollections().single()
        assertEquals(emptyList(), repository.getAllCollections())
        assertEquals("remote-created-collection-id", record.remote_id)
        assertEquals(1L, record.deleted)
        assertEquals(collection.localId, remaining.localID)
        assertEquals("remote-created-collection-id", remaining.remoteID)
        assertEquals(Mutation.DELETED, remaining.mutation)
    }

    @Test
    fun `remote created collection without ACK binds deleted match before active re-add`() = runTest {
        val deleted = repository.addCollection("Favorites", timestamp(1000L))
        val staleMutation = repository.fetchMutatedCollections().single()
        PersistenceImportRepositoryImpl(database).importData(
            data = PersistenceImportData(
                collections = listOf(
                    ImportCollection(
                        importId = "replacement-favorites",
                        name = "Favorites",
                        lastUpdated = timestamp(2000L)
                    )
                )
            ),
            deleteExisting = true
        )
        assertEquals(null, staleMutation.remoteID)

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("Favorites", timestamp(3000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val deletedRecord = database.collectionsQueries.getCollectionByLocalId(deleted.localId.toLong()).executeAsOne()
        val active = repository.getAllCollections().single()
        val remaining = repository.fetchMutatedCollections()
        assertEquals("remote-created-collection-id", deletedRecord.remote_id)
        assertEquals(1L, deletedRecord.deleted)
        assertEquals("Favorites", active.name)
        assertEquals(deleted.localId, remaining.single { it.mutation == Mutation.DELETED }.localID)
        assertEquals(active.localId, remaining.single { it.mutation == Mutation.CREATED }.localID)
    }

    @Test
    fun `ambiguous deleted collection replay candidates throw before persisting remote collection`() = runTest {
        val first = repository.addCollection("Favorites", timestamp(1000L))
        PersistenceImportRepositoryImpl(database).importData(
            data = PersistenceImportData(
                collections = listOf(
                    ImportCollection(
                        importId = "replacement-favorites",
                        name = "Favorites",
                        lastUpdated = timestamp(2000L)
                    )
                )
            ),
            deleteExisting = true
        )
        val second = repository.getAllCollections().single()
        repository.deleteCollection(second.localId)
        assertEquals(2, database.collectionsQueries.getPendingCreatedCollectionsByName("Favorites").executeAsList().size)
        assertEquals(1L, database.collectionsQueries.getCollectionByLocalId(first.localId.toLong()).executeAsOne().deleted)

        assertFailsWith<IllegalStateException> {
            repository.applyRemoteChanges(
                updatesToPersist = listOf(
                    RemoteModelMutation(
                        model = RemoteCollection("Favorites", timestamp(3000L)),
                        remoteID = "remote-created-collection-id",
                        mutation = Mutation.CREATED
                    )
                ),
                localMutationsToClear = emptyList()
            )
        }

        assertNull(database.collectionsQueries.getCollectionByRemoteId("remote-created-collection-id").executeAsOneOrNull())
    }

    @Test
    fun `re-added remote collection survives stale delete ACK as pending`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-collection-id",
            name = "Favorites",
            created_at = 1000L,
            modified_at = 1000L
        )
        val collection = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        repository.deleteCollection(collection.local_id.toString())
        val staleDelete = repository.fetchMutatedCollections().single()

        val readded = repository.addCollection("Favorites", timestamp(2000L))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(null, timestamp(1000L)),
                    remoteID = "remote-collection-id",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = listOf(staleDelete)
        )

        val record = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        val remaining = repository.fetchMutatedCollections().single()
        assertEquals(listOf(readded.localId), repository.getAllCollections().map { it.localId })
        assertEquals(0L, record.deleted)
        assertEquals(1L, record.is_edited)
        assertEquals(Mutation.MODIFIED, remaining.mutation)
        assertEquals(readded.localId, remaining.localID)
        assertEquals("remote-collection-id", remaining.remoteID)
    }

    private fun timestamp(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()
}

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.model.Collection
import com.quran.shared.persistence.model.DEFAULT_COLLECTION_NAME
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CollectionsRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: CollectionsRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        database.collectionsQueries.deleteAll()
        repository = CollectionsRepositoryImpl(database)
    }

    @Test
    fun `database seeds default and highlight system collections without mutations`() = runTest {
        val seededDatabase = QuranDatabase(TestDatabaseDriver().createDriver())
        val seededRepository = CollectionsRepositoryImpl(seededDatabase)

        val collections = seededRepository.getAllCollections()
        val defaultCollection = collections.single { it.isDefault }
        val highlights = collections.filter { it.isSystemHighlight }

        assertEquals(6, collections.size)
        assertEquals(DEFAULT_COLLECTION_NAME, defaultCollection.name)
        assertTrue(defaultCollection.isSystem)
        assertEquals(5, highlights.size)
        assertTrue(highlights.all { it.isSystem })
        assertEquals(emptyList(), seededRepository.fetchMutatedCollections())
    }

    @Test
    fun `addCollection rejects active system collection name without mutating seed`() = runTest {
        val seededDatabase = QuranDatabase(TestDatabaseDriver().createDriver())
        val seededRepository = CollectionsRepositoryImpl(seededDatabase)
        val original = seededDatabase.collectionsQueries.getDefaultCollection().executeAsOne()

        assertFailsWith<IllegalArgumentException> {
            seededRepository.addCollection(DEFAULT_COLLECTION_NAME, timestamp(1_000L))
        }

        val retained = seededDatabase.collectionsQueries.getDefaultCollection().executeAsOne()
        assertEquals(original, retained)
        assertEquals(1L, retained.is_default)
        assertEquals(1L, retained.is_system)
        assertEquals(0L, retained.pending_version)
        assertEquals(emptyList(), seededRepository.fetchMutatedCollections())
    }

    @Test
    fun `import rejects active system collection name without merging into seed`() = runTest {
        val seededDatabase = QuranDatabase(TestDatabaseDriver().createDriver())

        assertFailsWith<IllegalArgumentException> {
            PersistenceImportRepositoryImpl(seededDatabase).importData(
                data = PersistenceImportData(
                    collections = listOf(
                        ImportCollection(
                            importId = "custom-favorites",
                            name = DEFAULT_COLLECTION_NAME,
                            lastUpdated = timestamp(1_000L)
                        )
                    )
                ),
                deleteExisting = false
            )
        }

        val retained = seededDatabase.collectionsQueries.getDefaultCollection().executeAsOne()
        assertEquals(DEFAULT_COLLECTION_NAME, retained.name)
        assertEquals(1L, retained.is_default)
        assertEquals(1L, retained.is_system)
        assertEquals(0L, retained.pending_version)
        assertEquals(6L, seededDatabase.collectionsQueries.countAll().executeAsOne())
    }

    @Test
    fun `collection exposes persisted backend flags`() {
        val collection = Collection(
            name = "Favorites",
            lastUpdated = timestamp(1L),
            id = "1",
            isDefault = true,
            isSystem = true
        )

        assertTrue(collection.isDefault)
        assertTrue(collection.isSystem)
    }

    @Test
    fun `collection identifies system highlight names case insensitively`() {
        assertTrue(Collection(" SYSTEM:HIGHLIGHTS:GREEN ", timestamp(1L), "1").isSystemHighlight)
        assertFalse(Collection("Green", timestamp(1L), "2").isSystemHighlight)
    }

    @Test
    fun `collection CRUD restrictions use isSystem flag`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            repository.addCollection("system:highlights:blue", timestamp(100L))
        }
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "managed",
            name = "Managed",
            created_at = 100L,
            modified_at = 100L,
            is_default = 0L,
            is_system = 1L
        )
        val systemCollection = database.collectionsQueries
            .getCollectionByName("Managed")
            .executeAsOne()

        assertFailsWith<IllegalArgumentException> {
            repository.updateCollection(systemCollection.local_id.toString(), "Blue", timestamp(200L))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.deleteCollection(systemCollection.local_id.toString())
        }

        val retained = database.collectionsQueries
            .getCollectionByLocalId(systemCollection.local_id)
            .executeAsOne()
        assertEquals("Managed", retained.name)
        assertEquals(0L, retained.deleted)
    }

    @Test
    fun `remote default create binds seeded default by property`() = runTest {
        val seededDatabase = QuranDatabase(TestDatabaseDriver().createDriver())
        val seededRepository = CollectionsRepositoryImpl(seededDatabase)
        val localDefault = seededDatabase.collectionsQueries.getDefaultCollection().executeAsOne()

        seededRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(
                        name = "Favorites",
                        lastUpdated = timestamp(2_345L),
                        createdAt = timestamp(1_000L),
                        isDefault = true,
                        isSystem = true
                    ),
                    remoteID = "backend-default-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val remoteDefault = seededDatabase.collectionsQueries
            .getCollectionByRemoteId("backend-default-id")
            .executeAsOne()
        assertEquals(localDefault.local_id, remoteDefault.local_id)
        assertEquals("Favorites", remoteDefault.name)
        assertEquals(1L, remoteDefault.is_default)
        assertEquals(1L, remoteDefault.is_system)
        assertEquals(6L, seededDatabase.collectionsQueries.countAll().executeAsOne())
    }

    @Test
    fun `remote delete preserves default and non-default system collections`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-default",
            name = "Favorites",
            created_at = 1_000L,
            modified_at = 1_000L,
            is_default = 1L,
            is_system = 1L
        )
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-managed",
            name = "Managed",
            created_at = 1_000L,
            modified_at = 1_000L,
            is_default = 0L,
            is_system = 1L
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(null, timestamp(2_000L)),
                    remoteID = "remote-default",
                    mutation = Mutation.DELETED
                ),
                RemoteModelMutation(
                    model = RemoteCollection(null, timestamp(2_000L)),
                    remoteID = "remote-managed",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val retained = repository.getAllCollections()
        assertEquals(2, retained.size)
        assertEquals("Favorites", retained.single { it.isDefault }.name)
        assertTrue(retained.all { it.isSystem })
    }

    @Test
    fun `remote update cannot rename or demote existing system collection`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-managed",
            name = "Managed",
            created_at = 1_000L,
            modified_at = 1_000L,
            is_default = 0L,
            is_system = 1L
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(
                        name = "Renamed",
                        lastUpdated = timestamp(2_000L),
                        isDefault = true,
                        isSystem = false
                    ),
                    remoteID = "remote-managed",
                    mutation = Mutation.MODIFIED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val retained = database.collectionsQueries.getCollectionByRemoteId("remote-managed").executeAsOne()
        assertEquals("Managed", retained.name)
        assertEquals(0L, retained.is_default)
        assertEquals(1L, retained.is_system)
        assertEquals(2_000L, retained.modified_at)
    }

    @Test
    fun `collection rename rejects reserved system names`() = runTest {
        val collection = repository.addCollection("Study", timestamp(100L))

        assertFailsWith<IllegalArgumentException> {
            repository.updateCollection(collection.id, DEFAULT_COLLECTION_NAME, timestamp(200L))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.updateCollection(collection.id, "system:highlights:yellow", timestamp(200L))
        }

        assertEquals("Study", repository.getAllCollections().single().name)
    }

    @Test
    fun `addCollection respects explicit timestamp`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1234L))
        val record = database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOne()

        assertEquals(1234L, collection.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1234L, record.created_at)
        assertEquals(1234L, record.modified_at)
    }

    @Test
    fun `addCollection advances pending version for fresh create`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1234L))
        val record = database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOne()
        val mutation = repository.fetchMutatedCollections().single()

        assertEquals(1L, record.pending_version)
        assertEquals(1L, mutation.ack?.observedPendingVersion)
        assertEquals(Mutation.CREATED, mutation.mutation)
    }

    @Test
    fun `updateCollection respects explicit timestamp and preserves created_at`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))

        val updated = repository.updateCollection(collection.id, "Updated", timestamp(2345L))
        val record = database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOne()

        assertEquals(2345L, updated.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1000L, record.created_at)
        assertEquals(2345L, record.modified_at)
    }

    @Test
    fun `fetchMutatedCollections carries created_at separately from modified_at`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))
        repository.updateCollection(collection.id, "Updated", timestamp(2345L))

        val mutation = repository.fetchMutatedCollections().single()

        assertEquals(1000L, mutation.model.createdAt.fromPlatform().toEpochMilliseconds())
        assertEquals(2345L, mutation.model.lastUpdated.fromPlatform().toEpochMilliseconds())
    }

    @Test
    fun `remote created collection persists timestamps and system flags`() = runTest {
        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(
                        name = "Favorites",
                        lastUpdated = timestamp(2345L),
                        createdAt = timestamp(1000L),
                        isDefault = true,
                        isSystem = true
                    ),
                    remoteID = "remote-created-at-collection",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val record = database.collectionsQueries.getCollectionByRemoteId("remote-created-at-collection").executeAsOne()
        assertEquals(1000L, record.created_at)
        assertEquals(2345L, record.modified_at)
        assertEquals(1L, record.is_default)
        assertEquals(1L, record.is_system)
        val collection = repository.getAllCollections().single()
        assertTrue(collection.isDefault)
        assertTrue(collection.isSystem)
    }

    @Test
    fun `updateCollection rejects deleted collection without renaming tombstone`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))
        repository.deleteCollection(collection.id)

        assertFailsWith<IllegalArgumentException> {
            repository.updateCollection(collection.id, "Renamed", timestamp(2000L))
        }

        val tombstone = database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOne()
        assertEquals(CUSTOM_COLLECTION_NAME, tombstone.name)
        assertEquals(1L, tombstone.deleted)
        assertEquals(emptyList(), repository.getAllCollections())
        assertEquals(emptyList(), repository.fetchMutatedCollections())
    }

    @Test
    fun `updateCollection rejects pending remote delete without advancing mutation`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-collection-id",
            name = CUSTOM_COLLECTION_NAME,
            created_at = 1000L,
            modified_at = 1000L,
            is_default = 0L,
            is_system = 0L
        )
        val collection = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        repository.deleteCollection(collection.local_id.toString())
        val firstTombstone = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()

        assertFailsWith<IllegalArgumentException> {
            repository.updateCollection(collection.local_id.toString(), "Renamed", timestamp(2000L))
        }

        val secondTombstone = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        val mutation = repository.fetchMutatedCollections().single()
        assertEquals(CUSTOM_COLLECTION_NAME, secondTombstone.name)
        assertEquals(1L, secondTombstone.deleted)
        assertEquals(firstTombstone.pending_version, secondTombstone.pending_version)
        assertEquals(firstTombstone.modified_at, secondTombstone.modified_at)
        assertEquals(Mutation.DELETED, mutation.mutation)
    }

    @Test
    fun `deleteCollection updates timestamp for remote rows`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-collection-id",
            name = CUSTOM_COLLECTION_NAME,
            created_at = 1000L,
            modified_at = 1000L,
            is_default = 0L,
            is_system = 0L
        )
        val collection = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()

        repository.deleteCollection(collection.local_id.toString())

        val mutation = repository.fetchMutatedCollections().single()
        val record = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        assertEquals(Mutation.DELETED, mutation.mutation)
        assertTrue(mutation.model.lastUpdated.fromPlatform().toEpochMilliseconds() > 1000L)
        assertTrue(record.modified_at > 1000L)
    }

    @Test
    fun `deleteCollection returns false for missing collection without mutation`() = runTest {
        val deleted = repository.deleteCollection("999")

        assertFalse(deleted)
        assertEquals(emptyList(), repository.fetchMutatedCollections())
    }

    @Test
    fun `deleteCollection returns false for retained deleted collection without advancing mutation`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-collection-id",
            name = CUSTOM_COLLECTION_NAME,
            created_at = 1000L,
            modified_at = 1000L,
            is_default = 0L,
            is_system = 0L
        )
        val collection = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        assertEquals(true, repository.deleteCollection(collection.local_id.toString()))
        val firstTombstone = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        val firstMutation = repository.fetchMutatedCollections().single()

        val deletedAgain = repository.deleteCollection(collection.local_id.toString())

        val secondTombstone = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        assertFalse(deletedAgain)
        assertEquals(firstTombstone.pending_version, secondTombstone.pending_version)
        assertEquals(firstTombstone.modified_at, secondTombstone.modified_at)
        val secondMutation = repository.fetchMutatedCollections().single()
        assertEquals(firstMutation.model, secondMutation.model)
        assertEquals(firstMutation.remoteID, secondMutation.remoteID)
        assertEquals(firstMutation.localID, secondMutation.localID)
        assertEquals(firstMutation.mutation, secondMutation.mutation)
        assertEquals(firstMutation.ack, secondMutation.ack)
    }

    @Test
    fun `applyRemoteChanges clears collection ACK when pending version still matches`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-collection-id",
            name = CUSTOM_COLLECTION_NAME,
            created_at = 1000L,
            modified_at = 1000L,
            is_default = 0L,
            is_system = 0L
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
        assertEquals(2000L, record.modified_at)
        assertEquals(emptyList(), repository.fetchMutatedCollections())
    }

    @Test
    fun `applyRemoteChanges checks write boundary before collection transaction`() = runTest {
        assertFailsWith<IllegalStateException> {
            repository.applyRemoteChanges(
                updatesToPersist = listOf(
                    RemoteModelMutation(
                        model = RemoteCollection(CUSTOM_COLLECTION_NAME, timestamp(2000L)),
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
            name = CUSTOM_COLLECTION_NAME,
            created_at = 1000L,
            modified_at = 1000L,
            is_default = 0L,
            is_system = 0L
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
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))
        val staleMutation = repository.fetchMutatedCollections().single()
        repository.updateCollection(collection.id, "Renamed", timestamp(2000L))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedCollections().single()
        assertEquals("remote-created-collection-id", record.remote_id)
        assertEquals("Renamed", record.name)
        assertEquals(1L, record.is_edited)
        assertEquals(collection.id, remaining.localID)
        assertEquals("remote-created-collection-id", remaining.remoteID)
        assertEquals(Mutation.MODIFIED, remaining.mutation)
    }

    @Test
    fun `stale created collection ACK binds remote id and leaves delete pending`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))
        val staleMutation = repository.fetchMutatedCollections().single()
        repository.deleteCollection(collection.id)
        assertEquals(emptyList(), repository.getAllCollections())
        assertEquals(emptyList(), repository.fetchMutatedCollections())

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedCollections().single()
        assertEquals(emptyList(), repository.getAllCollections())
        assertEquals("remote-created-collection-id", record.remote_id)
        assertEquals(1L, record.deleted)
        assertEquals(collection.id, remaining.localID)
        assertEquals("remote-created-collection-id", remaining.remoteID)
        assertEquals(Mutation.DELETED, remaining.mutation)
    }

    @Test
    fun `deleteExisting import keeps local-created collection tombstone until create ACK binds`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))
        val staleMutation = repository.fetchMutatedCollections().single()
        PersistenceImportRepositoryImpl(database).importData(
            data = PersistenceImportData(
                collections = listOf(
                    ImportCollection(
                        importId = "imported-favorites",
                        name = CUSTOM_COLLECTION_NAME,
                        lastUpdated = timestamp(2000L)
                    )
                )
            ),
            deleteExisting = true
        )

        val tombstone = database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOne()
        val imported = database.collectionsQueries.getCollectionByName(CUSTOM_COLLECTION_NAME).executeAsOne()
        assertEquals(null, tombstone.remote_id)
        assertEquals(1L, tombstone.deleted)
        assertEquals(collection.id.toLong(), tombstone.local_id)
        assertEquals(1, repository.getAllCollections().size)
        assertEquals(1, repository.fetchMutatedCollections().size)
        assertEquals(CUSTOM_COLLECTION_NAME, imported.name)
        assertEquals(0L, imported.deleted)
        assertEquals(null, imported.remote_id)
        assertEquals(2000L, imported.modified_at)

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED,
                    ack = staleMutation.ack
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val record = database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedCollections()
        val tombstoneDelete = remaining.single { it.localID == collection.id }
        val importedCreate = remaining.single { it.localID != collection.id }
        assertEquals("remote-created-collection-id", record.remote_id)
        assertEquals(1L, record.deleted)
        assertEquals("remote-created-collection-id", tombstoneDelete.remoteID)
        assertEquals(Mutation.DELETED, tombstoneDelete.mutation)
        assertEquals(null, importedCreate.remoteID)
        assertEquals(Mutation.CREATED, importedCreate.mutation)
    }

    @Test
    fun `ACKed collection delete removes tombstone without reactivating colliding active name`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))
        val staleCreate = repository.fetchMutatedCollections().single()
        PersistenceImportRepositoryImpl(database).importData(
            data = PersistenceImportData(
                collections = listOf(
                    ImportCollection(
                        importId = "replacement-favorites",
                        name = CUSTOM_COLLECTION_NAME,
                        lastUpdated = timestamp(2000L)
                    )
                )
            ),
            deleteExisting = true
        )
        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED,
                    ack = staleCreate.ack
                )
            ),
            localMutationsToClear = listOf(staleCreate)
        )
        val staleDelete = repository.fetchMutatedCollections()
            .single { it.localID == collection.id }

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
        assertNull(database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOneOrNull())
        assertEquals(CUSTOM_COLLECTION_NAME, active.name)
        assertEquals(Mutation.CREATED, remainingMutation.mutation)
        assertEquals(active.id, remainingMutation.localID)
    }

    @Test
    fun `remote created collection without ACK does not move existing remote id by name`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-collection-1",
            name = CUSTOM_COLLECTION_NAME,
            created_at = 1000L,
            modified_at = 1000L,
            is_default = 0L,
            is_system = 0L
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(CUSTOM_COLLECTION_NAME, timestamp(2000L)),
                    remoteID = "remote-collection-2",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val original = database.collectionsQueries.getCollectionByRemoteId("remote-collection-1").executeAsOne()
        assertEquals(CUSTOM_COLLECTION_NAME, original.name)
        assertNull(database.collectionsQueries.getCollectionByRemoteId("remote-collection-2").executeAsOneOrNull())
    }

    @Test
    fun `remote created collection without ACK does not bind stale planned local create by name`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))
        val staleMutation = repository.fetchMutatedCollections().single()
        repository.updateCollection(collection.id, "Renamed", timestamp(2000L))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(staleMutation)
        )

        val localRecord = database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOne()
        val remoteRecord = database.collectionsQueries.getCollectionByRemoteId("remote-created-collection-id")
            .executeAsOne()
        val remaining = repository.fetchMutatedCollections().single()
        assertEquals(null, localRecord.remote_id)
        assertEquals("Renamed", localRecord.name)
        assertEquals(CUSTOM_COLLECTION_NAME, remoteRecord.name)
        assertEquals(collection.id, remaining.localID)
        assertEquals(Mutation.CREATED, remaining.mutation)
    }

    @Test
    fun `remote created collection without ACK binds unique current pending create by name`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(CUSTOM_COLLECTION_NAME, timestamp(2000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val record = database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOne()
        assertEquals("remote-created-collection-id", record.remote_id)
        assertEquals(0L, record.is_edited)
        assertEquals(1, repository.getAllCollections().size)
        assertEquals(emptyList(), repository.fetchMutatedCollections())
    }

    @Test
    fun `remote created collection without ACK binds deleted pending create by name and leaves delete pending`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))
        repository.deleteCollection(collection.id)
        assertEquals(emptyList(), repository.getAllCollections())
        assertEquals(emptyList(), repository.fetchMutatedCollections())

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection(CUSTOM_COLLECTION_NAME, timestamp(2000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val record = database.collectionsQueries.getCollectionByLocalId(collection.id.toLong()).executeAsOne()
        val remaining = repository.fetchMutatedCollections().single()
        assertEquals(emptyList(), repository.getAllCollections())
        assertEquals("remote-created-collection-id", record.remote_id)
        assertEquals(1L, record.deleted)
        assertEquals(collection.id, remaining.localID)
        assertEquals("remote-created-collection-id", remaining.remoteID)
        assertEquals(Mutation.DELETED, remaining.mutation)
    }

    @Test
    fun `remote created collection without ACK binds deleted match before active re-add`() = runTest {
        val deleted = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))
        val staleMutation = repository.fetchMutatedCollections().single()
        PersistenceImportRepositoryImpl(database).importData(
            data = PersistenceImportData(
                collections = listOf(
                    ImportCollection(
                        importId = "replacement-favorites",
                        name = CUSTOM_COLLECTION_NAME,
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
                    model = RemoteCollection(CUSTOM_COLLECTION_NAME, timestamp(3000L)),
                    remoteID = "remote-created-collection-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val deletedRecord = database.collectionsQueries.getCollectionByLocalId(deleted.id.toLong()).executeAsOne()
        val active = repository.getAllCollections().single()
        val remaining = repository.fetchMutatedCollections()
        assertEquals("remote-created-collection-id", deletedRecord.remote_id)
        assertEquals(1L, deletedRecord.deleted)
        assertEquals(CUSTOM_COLLECTION_NAME, active.name)
        assertEquals(deleted.id, remaining.single { it.mutation == Mutation.DELETED }.localID)
        assertEquals(active.id, remaining.single { it.mutation == Mutation.CREATED }.localID)
    }

    @Test
    fun `ambiguous deleted collection replay candidates throw before persisting remote collection`() = runTest {
        val first = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(1000L))
        PersistenceImportRepositoryImpl(database).importData(
            data = PersistenceImportData(
                collections = listOf(
                    ImportCollection(
                        importId = "replacement-favorites",
                        name = CUSTOM_COLLECTION_NAME,
                        lastUpdated = timestamp(2000L)
                    )
                )
            ),
            deleteExisting = true
        )
        val second = repository.getAllCollections().single()
        repository.deleteCollection(second.id)
        assertEquals(
            2,
            database.collectionsQueries.getPendingCreatedCollectionsByName(CUSTOM_COLLECTION_NAME).executeAsList().size
        )
        assertEquals(1L, database.collectionsQueries.getCollectionByLocalId(first.id.toLong()).executeAsOne().deleted)

        assertFailsWith<IllegalStateException> {
            repository.applyRemoteChanges(
                updatesToPersist = listOf(
                    RemoteModelMutation(
                        model = RemoteCollection(CUSTOM_COLLECTION_NAME, timestamp(3000L)),
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
            name = CUSTOM_COLLECTION_NAME,
            created_at = 1000L,
            modified_at = 1000L,
            is_default = 0L,
            is_system = 0L
        )
        val collection = database.collectionsQueries.getCollectionByRemoteId("remote-collection-id").executeAsOne()
        repository.deleteCollection(collection.local_id.toString())
        val staleDelete = repository.fetchMutatedCollections().single()

        val readded = repository.addCollection(CUSTOM_COLLECTION_NAME, timestamp(2000L))

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
        assertEquals(listOf(readded.id), repository.getAllCollections().map { it.id })
        assertEquals(0L, record.deleted)
        assertEquals(1L, record.is_edited)
        assertEquals(Mutation.MODIFIED, remaining.mutation)
        assertEquals(readded.id, remaining.localID)
        assertEquals("remote-collection-id", remaining.remoteID)
    }

    private fun timestamp(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()

    private companion object {
        const val CUSTOM_COLLECTION_NAME = "Study"
    }
}

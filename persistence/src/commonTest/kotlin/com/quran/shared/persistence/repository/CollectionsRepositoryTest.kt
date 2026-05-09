@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun timestamp(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()
}

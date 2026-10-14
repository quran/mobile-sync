package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.model.Collection
import com.quran.shared.persistence.model.DEFAULT_COLLECTION_NAME
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.util.fromPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionsRepositoryTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var repository: CollectionsRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        repository = CollectionsRepositoryImpl(database.synced)
    }

    @Test
    fun `a new database has only Favorites as a read-only system collection`() = runTest {
        val favorites = repository.getAllCollections().single()

        assertEquals(DEFAULT_COLLECTION_NAME, favorites.name)
        assertTrue(favorites.isDefault)
        assertTrue(favorites.isSystem)
        assertTrue(favorites.isReadOnly)
        assertFalse(favorites.isSystemHighlight)
    }

    @Test
    fun `addCollection rejects the Favorites name without changing Favorites`() = runTest {
        val original = favoritesRow()

        listOf(DEFAULT_COLLECTION_NAME, " favorites ", "FAVORITES").forEach { name ->
            assertFailsWith<IllegalArgumentException> { repository.addCollection(name, at(1_000L)) }
        }

        assertEquals(original, favoritesRow())
        assertEquals(1, repository.getAllCollections().size)
    }

    @Test
    fun `import recognizes Favorites and reuses the default collection`() = runTest {
        val original = favoritesRow()

        val result = PersistenceImportRepositoryImpl(database.quran, database.synced).importData(
            data = PersistenceImportData(collections = listOf(ImportCollection(name = " favorites ", lastUpdated = at(1_000L)))),
            deleteExisting = false
        )

        assertEquals(1, result.matched)
        assertEquals(0, result.collectionsImported)
        assertEquals(original, favoritesRow())
        assertEquals(1, repository.getAllCollections().size)
    }

    @Test
    fun `collection exposes persisted backend flags`() {
        val collection = Collection(name = "Favorites", lastUpdated = at(1L), id = "1", isDefault = true, isSystem = true)

        assertTrue(collection.isDefault)
        assertTrue(collection.isSystem)
        assertTrue(collection.isReadOnly)
    }

    @Test
    fun `a user collection is neither system nor read-only`() = runTest {
        val study = repository.addCollection("Study", at(1_000L))

        assertFalse(study.isDefault)
        assertFalse(study.isSystem)
        assertFalse(study.isReadOnly)
    }

    @Test
    fun `collection identifies system highlight names case insensitively`() {
        assertTrue(Collection(" SYSTEM:HIGHLIGHTS:GREEN ", at(1L), "1").isSystemHighlight)
        assertFalse(Collection("Green", at(1L), "2").isSystemHighlight)
    }

    @Test
    fun `addCollection rejects reserved system names`() = runTest {
        listOf("system:highlights:blue", "System:Highlights:Pink", " system:anything").forEach { name ->
            assertFailsWith<IllegalArgumentException> { repository.addCollection(name, at(100L)) }
        }

        assertEquals(listOf(DEFAULT_COLLECTION_NAME), repository.getAllCollections().map { it.name })
    }

    @Test
    fun `collection rename rejects reserved system names`() = runTest {
        val collection = repository.addCollection("Study", at(100L))

        assertFailsWith<IllegalArgumentException> {
            repository.updateCollection(collection.id, DEFAULT_COLLECTION_NAME, at(200L))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.updateCollection(collection.id, "system:highlights:yellow", at(200L))
        }

        assertEquals("Study", repository.getAllCollections().single { it.id == collection.id }.name)
    }

    @Test
    fun `addCollection respects explicit timestamp`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, at(1234L))
        val record = row(collection.id)

        assertEquals(1234L, collection.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1234L, record.created_at)
        assertEquals(1234L, record.updated_at)
    }

    @Test
    fun `updateCollection respects explicit timestamp and preserves created_at`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, at(1000L))

        val updated = repository.updateCollection(collection.id, "Updated", at(2345L))
        val record = row(collection.id)

        assertEquals("Updated", updated.name)
        assertEquals(2345L, updated.lastUpdated.fromPlatform().toEpochMilliseconds())
        assertEquals(1000L, record.created_at)
        assertEquals(2345L, record.updated_at)
    }

    @Test
    fun `updateCollection rejects a deleted collection`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, at(1000L))
        repository.deleteCollection(collection.id)

        assertFailsWith<IllegalArgumentException> {
            repository.updateCollection(collection.id, "Renamed", at(2000L))
        }

        assertNull(database.synced.collectionsQueries.collectionGet(collection.id.toLong()).executeAsOneOrNull())
        assertEquals(listOf(DEFAULT_COLLECTION_NAME), repository.getAllCollections().map { it.name })
    }

    @Test
    fun `deleteCollection removes the collection`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, at(1000L))

        assertTrue(repository.deleteCollection(collection.id))

        assertNull(database.synced.collectionsQueries.collectionGet(collection.id.toLong()).executeAsOneOrNull())
    }

    @Test
    fun `deleteCollection removes its verses and keeps their other memberships`() = runTest {
        val bookmarks = CollectionBookmarksRepositoryImpl(database.synced)
        val study = repository.addCollection("Study", at(100L))
        val review = repository.addCollection("Review", at(100L))
        bookmarks.addAyahBookmarkToCollection(study.id, 8, 16, at(200L))
        bookmarks.addAyahBookmarkToCollection(review.id, 8, 16, at(300L))

        repository.deleteCollection(study.id)

        assertEquals(
            listOf(review.id.toLong()),
            database.synced.collectionsQueries.collectionAyahsAt(8, 16).executeAsList().map { it.collection_local_id }
        )
        assertEquals(listOf(8 to 16), bookmarks.getBookmarksForCollection(review.id).map { it.sura to it.ayah })
    }

    @Test
    fun `deleteCollection returns false for missing collection`() = runTest {
        assertFalse(repository.deleteCollection("999"))
        assertFalse(repository.deleteCollection("not-a-number"))

        assertEquals(1, repository.getAllCollections().size)
    }

    @Test
    fun `deleteCollection returns false for an already deleted collection`() = runTest {
        val collection = repository.addCollection(CUSTOM_COLLECTION_NAME, at(1000L))
        assertTrue(repository.deleteCollection(collection.id))

        val deletedAgain = repository.deleteCollection(collection.id)

        assertFalse(deletedAgain)
        assertEquals(listOf(DEFAULT_COLLECTION_NAME), repository.getAllCollections().map { it.name })
    }

    @Test
    fun `getCollectionsFlow emits the current collections newest first`() = runTest {
        val study = repository.addCollection("Study", at(1000L))
        val memorize = repository.addCollection("Memorize", at(2000L))

        // Favorites is seeded with no creation time of its own, so it is the oldest; the app lists it first anyway.
        assertEquals(listOf("Memorize", "Study", DEFAULT_COLLECTION_NAME), repository.getCollectionsFlow().first().map { it.name })
        assertEquals(listOf(memorize, study), repository.getAllCollections().take(2))
    }

    private fun row(id: String) = database.synced.collectionsQueries.collectionGet(id.toLong()).executeAsOne()

    private fun favoritesRow() = database.synced.collectionsQueries.collectionDefault().executeAsOne()

    private companion object {
        const val CUSTOM_COLLECTION_NAME = "Custom"
    }
}

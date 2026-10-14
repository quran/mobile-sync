package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.ReadOnlyCollectionException
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.DEFAULT_COLLECTION_NAME
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadOnlyCollectionsTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var repository: CollectionsRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        repository = CollectionsRepositoryImpl(database.synced)
    }

    @Test
    fun `renaming Favorites throws a read-only error`() = runTest {
        val favorites = favorites()

        val error = assertFailsWith<ReadOnlyCollectionException> { repository.updateCollection(favorites.id, "Saved", at(200L)) }

        assertEquals(favorites.id, error.id)
        assertEquals(DEFAULT_COLLECTION_NAME, favorites().name)
    }

    @Test
    fun `deleting Favorites throws a read-only error`() = runTest {
        val favorites = favorites()

        assertFailsWith<ReadOnlyCollectionException> { repository.deleteCollection(favorites.id) }

        assertEquals(favorites, favorites())
    }

    @Test
    fun `renaming a highlight collection throws a read-only error`() = runTest {
        val highlight = highlightCollection(AyahHighlightColor.BLUE)

        assertFailsWith<ReadOnlyCollectionException> { repository.updateCollection(highlight.id, "Blue", at(200L)) }

        assertEquals(AyahHighlightColor.BLUE.collectionName, collection(highlight.id).name)
    }

    @Test
    fun `deleting a highlight collection throws a read-only error and keeps its verses`() = runTest {
        val highlight = highlightCollection(AyahHighlightColor.BLUE)

        assertFailsWith<ReadOnlyCollectionException> { repository.deleteCollection(highlight.id) }

        assertEquals(1, database.synced.collectionsQueries.collectionAyahsIn(highlight.id.toLong()).executeAsList().size)
    }

    @Test
    fun `a highlight collection is a read-only system collection`() = runTest {
        val highlight = highlightCollection(AyahHighlightColor.GREEN)

        assertTrue(highlight.isSystem)
        assertTrue(highlight.isReadOnly)
        assertTrue(highlight.isSystemHighlight)
        assertFalse(highlight.isDefault)
    }

    @Test
    fun `renaming a protected collection throws a read-only error`() = runTest {
        val green = insertCollection("Green", system = false, readOnly = true)

        assertFailsWith<ReadOnlyCollectionException> { repository.updateCollection(green, "Teal", at(200L)) }

        assertEquals("Green", collection(green).name)
    }

    @Test
    fun `deleting a protected collection throws a read-only error`() = runTest {
        val green = insertCollection("Green", system = false, readOnly = true)

        assertFailsWith<ReadOnlyCollectionException> { repository.deleteCollection(green) }

        assertEquals("Green", collection(green).name)
    }

    @Test
    fun `a protected collection is read-only without being a system collection`() = runTest {
        val green = collection(insertCollection("Green", system = false, readOnly = true))

        assertTrue(green.isReadOnly)
        assertFalse(green.isSystem)
    }

    @Test
    fun `renaming a server system collection throws a read-only error`() = runTest {
        val managed = insertCollection("Managed", system = true, readOnly = false)

        assertFailsWith<ReadOnlyCollectionException> { repository.updateCollection(managed, "Blue", at(200L)) }

        assertEquals("Managed", collection(managed).name)
    }

    @Test
    fun `deleting a server system collection throws a read-only error`() = runTest {
        val managed = insertCollection("Managed", system = true, readOnly = false)

        assertFailsWith<ReadOnlyCollectionException> { repository.deleteCollection(managed) }

        val retained = collection(managed)
        assertTrue(retained.isSystem)
        assertTrue(retained.isReadOnly)
    }

    private suspend fun favorites() = repository.getAllCollections().single { it.isDefault }

    private suspend fun collection(id: String) = repository.getAllCollections().single { it.id == id }

    private suspend fun highlightCollection(color: AyahHighlightColor) = CollectionBookmarksRepositoryImpl(database.synced)
        .setHighlight(2, 255, color, at(100L))
        .let { repository.getAllCollections().single { it.name == color.collectionName } }

    // In one transaction, so last_insert_rowid() runs on the connection that inserted.
    private fun insertCollection(name: String, system: Boolean, readOnly: Boolean): String =
        database.synced.transactionWithResult {
            database.synced.collectionsQueries.collectionInsert(
                name, name.lowercase(), 0, if (system) 1 else 0, if (readOnly) 1 else 0, 100L, 100L
            )
            database.synced.collectionsQueries.lastInsertId().executeAsOne().toString()
        }
}

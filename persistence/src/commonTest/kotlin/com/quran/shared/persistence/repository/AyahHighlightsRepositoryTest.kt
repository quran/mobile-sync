package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.held
import com.quran.shared.persistence.markSynced
import com.quran.shared.persistence.model.AyahHighlight
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.pending
import com.quran.shared.persistence.planPush
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.syncv2.types.syncTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AyahHighlightsRepositoryTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var collectionsRepository: CollectionsRepositoryImpl
    private lateinit var collectionBookmarksRepository: CollectionBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        collectionsRepository = CollectionsRepositoryImpl(database.synced)
        collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(database.synced)
    }

    @Test
    fun `highlight colors use fixed system collection names`() {
        assertEquals(
            listOf(
                "system:highlights:blue",
                "system:highlights:pink",
                "system:highlights:green",
                "system:highlights:yellow",
                "system:highlights:purple"
            ),
            AyahHighlightColor.entries.map { it.collectionName }
        )
    }

    @Test
    fun `setHighlight creates the color's system collection on first use`() = runTest {
        val highlight = collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.BLUE, at(1_234))

        assertEquals(AyahHighlight(2, 255, AyahHighlightColor.BLUE, at(1_234)), highlight)
        val collection = collectionsRepository.getAllCollections().single { it.name == "system:highlights:blue" }
        assertFalse(collection.isDefault)
        assertTrue(collection.isSystem)
        assertEquals(
            listOf(2 to 255),
            collectionBookmarksRepository.getBookmarksForCollection(collection.id).map { it.sura to it.ayah }
        )
        assertEquals(listOf(highlight), collectionBookmarksRepository.getHighlightsFlow().first())
    }

    @Test
    fun `later highlights reuse the color's collection`() = runTest {
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.BLUE, at(100))
        collectionBookmarksRepository.setHighlight(3, 1, AyahHighlightColor.BLUE, at(200))

        val blue = collectionsRepository.getAllCollections().filter { it.name == "system:highlights:blue" }
        assertEquals(1, blue.size)
        assertEquals(2, collectionBookmarksRepository.getBookmarksForCollection(blue.single().id).size)
    }

    @Test
    fun `highlights flow reports one latest color per ayah`() = runTest {
        collectionBookmarksRepository.setHighlight(1, 1, AyahHighlightColor.BLUE, at(100))
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.GREEN, at(200))
        collectionBookmarksRepository.setHighlight(1, 1, AyahHighlightColor.PINK, at(300))

        assertEquals(
            listOf(
                AyahHighlight(1, 1, AyahHighlightColor.PINK, at(300)),
                AyahHighlight(2, 255, AyahHighlightColor.GREEN, at(200))
            ),
            collectionBookmarksRepository.getHighlightsFlow().first()
        )
    }

    @Test
    fun `a verse keeps one highlight color`() = runTest {
        AyahHighlightColor.entries.forEachIndexed { index, color ->
            collectionBookmarksRepository.setHighlight(2, 255, color, at(100L + index))
        }

        val highlightCollections = collectionsRepository.getAllCollections().filter { it.isSystemHighlight }
        val memberships = database.synced.collectionsQueries.collectionAyahsAt(2, 255).executeAsList()
        assertEquals(5, highlightCollections.size)
        assertEquals(
            listOf(highlightCollections.single { it.name == "system:highlights:purple" }.id.toLong()),
            memberships.map { it.collection_local_id }
        )
    }

    @Test
    fun `setting the same color again keeps the highlight's date`() = runTest {
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.PINK, at(100))

        val again = collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.PINK, at(150))

        assertEquals(AyahHighlight(2, 255, AyahHighlightColor.PINK, at(100)), again)
        assertEquals(listOf(again), collectionBookmarksRepository.getHighlightsFlow().first())
    }

    @Test
    fun `setHighlight replaces color and preserves default and user collections`() = runTest {
        collectionBookmarksRepository.addAyahBookmarkToCollection(defaultCollectionId(), 2, 255, at(100))
        val userCollection = collectionsRepository.addCollection("Study", at(200))
        collectionBookmarksRepository.addAyahBookmarkToCollection(userCollection.id, 2, 255, at(300))
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.GREEN, at(400))

        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.PURPLE, at(500))

        val collectionsByName = collectionsRepository.getAllCollections().associateBy { it.name }
        val green = requireNotNull(collectionsByName["system:highlights:green"])
        val purple = requireNotNull(collectionsByName["system:highlights:purple"])
        assertEquals(emptyList(), collectionBookmarksRepository.getBookmarksForCollection(green.id))
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(purple.id).size)
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(userCollection.id).size)
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(defaultCollectionId()).size)
    }

    @Test
    fun `deleteHighlight preserves default and user collections`() = runTest {
        collectionBookmarksRepository.addAyahBookmarkToCollection(defaultCollectionId(), 2, 255, at(100))
        val userCollection = collectionsRepository.addCollection("Study", at(200))
        collectionBookmarksRepository.addAyahBookmarkToCollection(userCollection.id, 2, 255, at(300))
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.YELLOW, at(400))

        val removed = collectionBookmarksRepository.deleteHighlight(2, 255, at(500))

        val highlightCollection = collectionsRepository.getAllCollections().single { it.name == "system:highlights:yellow" }
        assertTrue(removed)
        assertEquals(emptyList(), collectionBookmarksRepository.getBookmarksForCollection(highlightCollection.id))
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(userCollection.id).size)
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(defaultCollectionId()).size)
        assertFalse(collectionBookmarksRepository.deleteHighlight(2, 255, at(600)))
    }

    @Test
    fun `deleteHighlight leaves a highlight-only verse in no collection`() = runTest {
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.PINK, at(100))
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.PINK, at(150))

        assertTrue(collectionBookmarksRepository.deleteHighlight(2, 255, at(200)))

        assertEquals(emptyList(), database.synced.collectionsQueries.collectionAyahsAt(2, 255).executeAsList())
    }

    @Test
    fun `deleteHighlight convenience overload supplies the mutation timestamp`() = runTest {
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.BLUE, at(100))

        assertTrue(collectionBookmarksRepository.deleteHighlight(2, 255))
        assertEquals(emptyList(), collectionBookmarksRepository.getHighlightsFlow().first())
    }

    @Test
    fun `deleteHighlights removes only that color and preserves other colors and collections`() = runTest {
        collectionBookmarksRepository.addAyahBookmarkToCollection(defaultCollectionId(), 2, 255, at(100))
        val study = collectionsRepository.addCollection("Study", at(200))
        collectionBookmarksRepository.addAyahBookmarkToCollection(study.id, 2, 255, at(300))
        collectionBookmarksRepository.addAyahBookmarkToCollection(study.id, 3, 18, at(300))
        listOf(2 to 255, 3 to 18, 4 to 1).forEach { (sura, ayah) ->
            collectionBookmarksRepository.setHighlight(sura, ayah, AyahHighlightColor.BLUE, at(400))
        }
        collectionBookmarksRepository.setHighlight(5, 3, AyahHighlightColor.GREEN, at(500))

        val removed = collectionBookmarksRepository.deleteHighlights(AyahHighlightColor.BLUE)

        assertEquals(3, removed)
        assertEquals(listOf(AyahHighlight(5, 3, AyahHighlightColor.GREEN, at(500))), collectionBookmarksRepository.getHighlightsFlow().first())
        val blue = collectionsRepository.getAllCollections().single { it.name == "system:highlights:blue" }
        assertEquals(emptyList(), collectionBookmarksRepository.getBookmarksForCollection(blue.id), "the blue collection stays, empty")
        assertEquals(listOf("2:255"), collectionBookmarksRepository.getBookmarksForCollection(defaultCollectionId()).map { it.bookmarkId })
        assertEquals(
            setOf("2:255", "3:18"),
            collectionBookmarksRepository.getBookmarksForCollection(study.id).map { it.bookmarkId }.toSet()
        )
    }

    @Test
    fun `deleteHighlights returns 0 when no verse has the color`() = runTest {
        assertEquals(0, collectionBookmarksRepository.deleteHighlights(AyahHighlightColor.PINK), "no pink collection yet")

        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.PINK, at(100))
        collectionBookmarksRepository.setHighlight(3, 18, AyahHighlightColor.GREEN, at(200))
        collectionBookmarksRepository.deleteHighlight(2, 255, at(300))
        database.markSyncedAll()

        assertEquals(0, collectionBookmarksRepository.deleteHighlights(AyahHighlightColor.PINK), "an empty pink collection")
        assertEquals(listOf(AyahHighlight(3, 18, AyahHighlightColor.GREEN, at(200))), collectionBookmarksRepository.getHighlightsFlow().first())
        assertEquals(emptyList(), database.pending(), "nothing to push")
    }

    @Test
    fun `deleteHighlights leaves the same changes to push as deleting each highlight`() = runTest {
        val bulk = highlightedDatabase()
        val single = highlightedDatabase()

        assertEquals(3, CollectionBookmarksRepositoryImpl(bulk.synced).deleteHighlights(AyahHighlightColor.BLUE))
        CollectionBookmarksRepositoryImpl(single.synced).apply {
            listOf(2 to 255, 3 to 18, 5 to 5).forEach { (sura, ayah) -> assertTrue(deleteHighlight(sura, ayah)) }
        }

        assertEquals(single.pending(), bulk.pending())
        assertEquals(2, bulk.pending().size, "one DELETE per synced highlight; the never-pushed 5:5 sends nothing")
        assertEquals(single.held(), bulk.held())
        assertEquals(listOf("INVALID_VERSE"), bulk.held().map { it.reason }, "2:999 stays held")
        assertEquals(
            single.synced.collectionsQueries.collectionAyahsAll().executeAsList(),
            bulk.synced.collectionsQueries.collectionAyahsAll().executeAsList()
        )
    }

    @Test
    fun `deleteHighlights keeps a verse that doesn't exist and doesn't count it`() = runTest {
        collectionBookmarksRepository.setHighlight(2, 999, AyahHighlightColor.BLUE, at(100))
        collectionBookmarksRepository.setHighlight(1, 8, AyahHighlightColor.BLUE, at(100))
        database.markSyncedAll()

        assertEquals(0, collectionBookmarksRepository.deleteHighlights(AyahHighlightColor.BLUE))

        assertEquals(
            listOf(2L to 999L, 1L to 8L),
            database.synced.collectionsQueries.collectionAyahsAll().executeAsList().map { it.sura to it.ayah }
        )
        assertEquals(emptyList(), database.pending(), "no DELETE the server would refuse")
    }

    /**
     * Synced: blue on 2:255 and 3:18, green on 4:1, 2:255 in Favorites. Then, never pushed: blue on 5:5, and blue on
     * 2:999, which doesn't exist, so its create is held.
     */
    private suspend fun highlightedDatabase(): MobileSyncDatabase = createTestDatabase().apply {
        val repository = CollectionBookmarksRepositoryImpl(synced)
        val favorites = synced.collectionsQueries.collectionDefault().executeAsOne().local_id.toString()
        repository.addAyahBookmarkToCollection(favorites, 2, 255, at(100))
        repository.setHighlight(2, 255, AyahHighlightColor.BLUE, at(200))
        repository.setHighlight(3, 18, AyahHighlightColor.BLUE, at(300))
        repository.setHighlight(4, 1, AyahHighlightColor.GREEN, at(400))
        markSyncedAll()
        repository.setHighlight(5, 5, AyahHighlightColor.BLUE, at(500))
        repository.setHighlight(2, 999, AyahHighlightColor.BLUE, at(600))
        planPush()
        assertEquals(listOf("INVALID_VERSE"), held().map { it.reason })
    }

    private fun MobileSyncDatabase.markSyncedAll() = syncTypes(synced).forEach(::markSynced)

    private fun defaultCollectionId(): String =
        database.synced.collectionsQueries.collectionDefault().executeAsOne().local_id.toString()
}

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.model.AyahHighlight
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AyahHighlightsRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var bookmarksRepository: BookmarksRepositoryImpl
    private lateinit var collectionsRepository: CollectionsRepositoryImpl
    private lateinit var collectionBookmarksRepository: CollectionBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        bookmarksRepository = BookmarksRepositoryImpl(database)
        collectionsRepository = CollectionsRepositoryImpl(database)
        collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(database)
    }

    @Test
    fun `highlight colors use fixed system collection names`() {
        assertEquals(
            listOf(
                "system:highlights:blue",
                "system:highlights:red",
                "system:highlights:green",
                "system:highlights:yellow",
                "system:highlights:purple"
            ),
            AyahHighlightColor.entries.map { it.collectionName }
        )
    }

    @Test
    fun `setHighlight activates seeded namespaced collection and highlight-only bookmark`() = runTest {
        val timestamp = timestamp(1_234)

        val highlight = collectionBookmarksRepository.setHighlight(
            sura = 2,
            ayah = 255,
            color = AyahHighlightColor.BLUE,
            timestamp = timestamp
        )

        assertEquals(AyahHighlight(2, 255, AyahHighlightColor.BLUE, timestamp), highlight)
        val collection = collectionsRepository.getAllCollections()
            .single { it.name == "system:highlights:blue" }
        assertEquals("system:highlights:blue", collection.name)
        assertEquals(
            listOf(2 to 255),
            collectionBookmarksRepository.getBookmarksForCollection(collection.id).map { it.sura to it.ayah }
        )
        assertFalse(collection.isDefault)
        assertTrue(collection.isSystem)
        assertEquals(
            listOf("system:highlights:blue"),
            collectionsRepository.fetchMutatedCollections().map { it.model.name }
        )
        assertEquals(listOf(highlight), collectionBookmarksRepository.getHighlightsFlow().first())
    }

    @Test
    fun `highlights flow reports one latest color per ayah`() = runTest {
        collectionBookmarksRepository.setHighlight(1, 1, AyahHighlightColor.BLUE, timestamp(100))
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.GREEN, timestamp(200))
        collectionBookmarksRepository.setHighlight(1, 1, AyahHighlightColor.RED, timestamp(300))

        assertEquals(
            listOf(
                AyahHighlight(1, 1, AyahHighlightColor.RED, timestamp(300)),
                AyahHighlight(2, 255, AyahHighlightColor.GREEN, timestamp(200))
            ),
            collectionBookmarksRepository.getHighlightsFlow().first()
        )
    }

    @Test
    fun `setHighlight replaces color and preserves default and user collections`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(2, 255, timestamp(100))
        val userCollection = collectionsRepository.addCollection("Study", timestamp(200))
        collectionBookmarksRepository.addBookmarkToCollection(userCollection.id, bookmark, timestamp(300))
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.GREEN, timestamp(400))

        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.PURPLE, timestamp(500))

        val collectionsByName = collectionsRepository.getAllCollections().associateBy { it.name }
        val green = requireNotNull(collectionsByName["system:highlights:green"])
        val purple = requireNotNull(collectionsByName["system:highlights:purple"])
        assertEquals(emptyList(), collectionBookmarksRepository.getBookmarksForCollection(green.id))
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(purple.id).size)
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(userCollection.id).size)
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(defaultCollectionId()).size)
    }

    @Test
    fun `removeHighlight preserves default and user collections`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(2, 255, timestamp(100))
        val userCollection = collectionsRepository.addCollection("Study", timestamp(200))
        collectionBookmarksRepository.addBookmarkToCollection(userCollection.id, bookmark, timestamp(300))
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.YELLOW, timestamp(400))

        val removed = collectionBookmarksRepository.removeHighlight(2, 255, timestamp(500))

        val highlightCollection = collectionsRepository.getAllCollections()
            .single { it.name == "system:highlights:yellow" }
        assertTrue(removed)
        assertEquals(emptyList(), collectionBookmarksRepository.getBookmarksForCollection(highlightCollection.id))
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(userCollection.id).size)
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(defaultCollectionId()).size)
        assertFalse(collectionBookmarksRepository.removeHighlight(2, 255, timestamp(600)))
    }

    @Test
    fun `removeHighlight prunes a local highlight-only bookmark`() = runTest {
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.RED, timestamp(100))
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.RED, timestamp(150))

        assertTrue(collectionBookmarksRepository.removeHighlight(2, 255, timestamp(200)))

        assertNull(database.bookmarksQueries.getBookmarkForAyah(2L, 255L).executeAsOneOrNull())
    }

    private fun timestamp(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()

    private fun defaultCollectionId(): String =
        database.collectionsQueries.getDefaultCollection().executeAsOne().local_id.toString()
}

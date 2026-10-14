package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.fromPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookmarksRepositoryTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var bookmarksRepository: BookmarksRepositoryImpl
    private lateinit var collectionsRepository: CollectionsRepositoryImpl
    private lateinit var collectionBookmarksRepository: CollectionBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        bookmarksRepository = BookmarksRepositoryImpl(database.synced)
        collectionsRepository = CollectionsRepositoryImpl(database.synced)
        collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(database.synced)
    }

    @Test
    fun `replaceAyahBookmarkCollections with timestamp applies timestamp when creating missing bookmark`() = runTest {
        val collectionId = createCollection("ReplaceAyahTimestamp")

        val result = bookmarksRepository.replaceAyahBookmarkCollections(2, 14, listOf(collectionId), at(4300))

        val bookmark = assertNotNull(result.bookmark)
        assertTrue(result.changed)
        assertEquals("2:14", bookmark.id)
        assertEquals(4300L, bookmark.addedDate.millis())
        assertEquals(4300L, bookmark.lastUpdated.millis())
        assertEquals(listOf(collectionId.toLong() to 4300L), memberships(2, 14).map { it.collection_local_id to it.created_at })
    }

    @Test
    fun `first saved collection updates an existing highlight bookmark timestamp`() = runTest {
        val collectionId = createCollection("First Saved Membership")
        collectionBookmarksRepository.setHighlight(2, 15, AyahHighlightColor.GREEN, at(100))

        val result = bookmarksRepository.replaceAyahBookmarkCollections(2, 15, listOf(collectionId), at(200))

        val resultBookmark = assertNotNull(result.bookmark)
        val membership = collectionBookmarksRepository.getBookmarksForCollection(collectionId).single()
        assertTrue(result.changed)
        assertEquals("2:15", resultBookmark.id)
        assertEquals(100L, resultBookmark.addedDate.millis())
        assertEquals(200L, resultBookmark.lastUpdated.millis())
        assertEquals(200L, membership.bookmarkLastUpdated.millis())
        assertEquals(100L, membership.bookmarkAddedDate.millis())
        assertEquals(AyahHighlightColor.GREEN, collectionBookmarksRepository.getHighlightsFlow().first().single().color)
    }

    @Test
    fun `later saved collection replacements do not rewrite the bookmark timestamp`() = runTest {
        val firstCollectionId = createCollection("First Saved Collection")
        val secondCollectionId = createCollection("Second Saved Collection")
        collectionBookmarksRepository.addAyahBookmarkToCollection(firstCollectionId, 2, 16, at(100))

        val changed = bookmarksRepository.replaceAyahBookmarkCollections(2, 16, listOf(secondCollectionId), at(200))
        val unchanged = bookmarksRepository.replaceAyahBookmarkCollections(2, 16, listOf(secondCollectionId), at(300))

        assertTrue(changed.changed)
        assertFalse(unchanged.changed)
        assertEquals(100L, assertNotNull(changed.bookmark).lastUpdated.millis())
        assertEquals(100L, assertNotNull(unchanged.bookmark).lastUpdated.millis())
    }

    @Test
    fun `replacing with the current collections reports no change`() = runTest {
        val collectionId = createCollection("Unchanged")
        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 16, at(100))

        val result = bookmarksRepository.replaceAyahBookmarkCollections(2, 16, listOf(" $collectionId ", ""), at(200))

        assertFalse(result.changed)
        assertEquals(100L, assertNotNull(result.bookmark).lastUpdated.millis())
        assertEquals(listOf(100L), memberships(2, 16).map { it.created_at })
    }

    @Test
    fun `empty collection replacement removes an existing saved bookmark`() = runTest {
        val collectionId = createCollection("RemoveWithEmptyReplacement")
        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 15, at(100))

        val result = bookmarksRepository.replaceAyahBookmarkCollections(2, 15, emptyList(), at(200))

        assertTrue(result.changed)
        assertNull(result.bookmark)
        assertEquals(emptyList(), memberships(2, 15))
    }

    @Test
    fun `empty collection replacement does nothing when bookmark is missing`() = runTest {
        val result = bookmarksRepository.replaceAyahBookmarkCollections(2, 16, emptyList(), at(200))

        assertFalse(result.changed)
        assertNull(result.bookmark)
        assertEquals(emptyList(), database.synced.collectionsQueries.collectionAyahsAll().executeAsList())
    }

    @Test
    fun `collection replacement preserves highlight membership`() = runTest {
        val firstCollectionId = createCollection("ReplacePreserveHighlightFirst")
        val secondCollectionId = createCollection("ReplacePreserveHighlightSecond")
        collectionBookmarksRepository.setHighlight(2, 18, AyahHighlightColor.GREEN, at(100))
        collectionBookmarksRepository.addAyahBookmarkToCollection(firstCollectionId, 2, 18, at(100))

        val result = bookmarksRepository.replaceAyahBookmarkCollections(2, 18, listOf(secondCollectionId), at(200))

        assertTrue(result.changed)
        assertNotNull(result.bookmark)
        assertEquals(
            setOf(highlightCollectionId(AyahHighlightColor.GREEN), secondCollectionId.toLong()),
            memberships(2, 18).map { it.collection_local_id }.toSet()
        )
        assertEquals(AyahHighlightColor.GREEN, collectionBookmarksRepository.getHighlightsFlow().first().single().color)
    }

    @Test
    fun `empty collection replacement preserves highlight membership`() = runTest {
        val collectionId = createCollection("RemovePreserveHighlight")
        collectionBookmarksRepository.setHighlight(2, 19, AyahHighlightColor.PURPLE, at(100))
        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 19, at(100))

        val result = bookmarksRepository.replaceAyahBookmarkCollections(2, 19, emptyList(), at(200))

        assertTrue(result.changed)
        assertNull(result.bookmark)
        assertEquals(listOf(highlightCollectionId(AyahHighlightColor.PURPLE)), memberships(2, 19).map { it.collection_local_id })
        assertEquals(AyahHighlightColor.PURPLE, collectionBookmarksRepository.getHighlightsFlow().first().single().color)
    }

    @Test
    fun `a highlight collection in the request is ignored`() = runTest {
        collectionBookmarksRepository.setHighlight(3, 1, AyahHighlightColor.BLUE, at(100))
        val blue = highlightCollectionId(AyahHighlightColor.BLUE).toString()

        val result = bookmarksRepository.replaceAyahBookmarkCollections(2, 20, listOf(blue), at(200))

        assertFalse(result.changed)
        assertNull(result.bookmark)
        assertEquals(emptyList(), memberships(2, 20))
    }

    @Test
    fun `an unknown collection in the request fails without writing`() = runTest {
        val collectionId = createCollection("Known")

        assertFailsWith<IllegalArgumentException> {
            bookmarksRepository.replaceAyahBookmarkCollections(2, 21, listOf(collectionId, "999"), at(200))
        }

        assertEquals(emptyList(), memberships(2, 21))
    }

    private suspend fun createCollection(name: String): String = collectionsRepository.addCollection(name, at(50)).id

    private suspend fun highlightCollectionId(color: AyahHighlightColor): Long =
        collectionsRepository.getAllCollections().single { it.name == color.collectionName }.id.toLong()

    private fun memberships(sura: Long, ayah: Long) =
        database.synced.collectionsQueries.collectionAyahsAt(sura, ayah).executeAsList()

    private fun PlatformDateTime.millis() = fromPlatform().toEpochMilliseconds()
}

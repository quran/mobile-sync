package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.model.AyahHighlightColor
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
import kotlin.test.assertTrue

class CollectionBookmarksRepositoryTest {
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
    fun `offline bookmark joins the Favorites collection`() = runTest {
        val favoritesId = database.synced.collectionsQueries.collectionDefault().executeAsOne().local_id.toString()

        collectionBookmarksRepository.addAyahBookmarkToCollection(favoritesId, 2, 255, at(100))

        val bookmarks = collectionBookmarksRepository.getBookmarksForCollection(favoritesId)
        assertEquals(listOf(2 to 255), bookmarks.map { it.sura to it.ayah })
    }

    @Test
    fun `an ayah bookmark id is its verse in every collection`() = runTest {
        val study = createCollection("Study")
        val review = createCollection("Review")

        val inStudy = collectionBookmarksRepository.addAyahBookmarkToCollection(study, 2, 255, at(100))
        val inReview = collectionBookmarksRepository.addAyahBookmarkToCollection(review, 2, 255, at(200))

        assertEquals("2:255", inStudy.bookmarkId)
        assertEquals("2:255", inReview.bookmarkId)
    }

    @Test
    fun `adding a verse to a collection dates its membership and bookmark`() = runTest {
        val collectionId = createCollection("Study")

        val membership = collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 255, at(100))

        assertEquals(collectionId, membership.collectionId)
        assertEquals(100L, membership.bookmarkAddedDate.millis())
        assertEquals(100L, membership.bookmarkLastUpdated.millis())
        assertEquals(100L, memberships(2, 255).single().created_at)
    }

    @Test
    fun `adding first saved collection updates an existing highlight bookmark timestamp`() = runTest {
        val collectionId = createCollection("Direct First Saved Membership")
        collectionBookmarksRepository.setHighlight(2, 17, AyahHighlightColor.PURPLE, at(100))

        val membership = collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 17, at(200))

        assertEquals(200L, membership.bookmarkLastUpdated.millis())
        assertEquals(100L, membership.bookmarkAddedDate.millis())
        assertEquals(AyahHighlightColor.PURPLE, collectionBookmarksRepository.getHighlightsFlow().first().single().color)
    }

    @Test
    fun `adding later saved collections preserves the bookmark timestamp`() = runTest {
        val firstCollectionId = createCollection("Direct Existing First")
        val secondCollectionId = createCollection("Direct Existing Second")
        collectionBookmarksRepository.addAyahBookmarkToCollection(firstCollectionId, 2, 20, at(100))

        collectionBookmarksRepository.addAyahBookmarkToCollection(secondCollectionId, 2, 20, at(200))
        collectionBookmarksRepository.addAyahBookmarkToCollection(firstCollectionId, 2, 20, at(300))

        val inSecond = collectionBookmarksRepository.getBookmarksForCollection(secondCollectionId).single()
        assertEquals(100L, inSecond.bookmarkAddedDate.millis())
        assertEquals(100L, inSecond.bookmarkLastUpdated.millis())
        assertEquals(listOf(100L, 200L), memberships(2, 20).map { it.created_at })
    }

    @Test
    fun `addAyahBookmarkToCollection rejects a highlight collection`() = runTest {
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.BLUE, at(100))
        val blue = collectionsRepository.getAllCollections().single { it.isSystemHighlight }

        assertFailsWith<IllegalArgumentException> {
            collectionBookmarksRepository.addAyahBookmarkToCollection(blue.id, 3, 1, at(200))
        }

        assertEquals(emptyList(), memberships(3, 1))
    }

    @Test
    fun `addAyahBookmarkToCollection rejects an unknown collection`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            collectionBookmarksRepository.addAyahBookmarkToCollection("999", 3, 1, at(200))
        }

        assertEquals(emptyList(), memberships(3, 1))
    }

    @Test
    fun `getBookmarksForCollection lists the most recently added verse first`() = runTest {
        val collectionId = createCollection("Study")
        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 3, 1, at(100))
        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 255, at(300))
        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 1, 1, at(200))

        assertEquals(
            listOf("2:255", "1:1", "3:1"),
            collectionBookmarksRepository.getBookmarksForCollection(collectionId).map { it.bookmarkId }
        )
    }

    @Test
    fun `getBookmarksForCollectionFlow emits the collection's verses`() = runTest {
        val collectionId = createCollection("Study")
        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 255, at(100))

        assertEquals(
            listOf(2 to 255),
            collectionBookmarksRepository.getBookmarksForCollectionFlow(collectionId).first().map { it.sura to it.ayah }
        )
    }

    @Test
    fun `removing a collection bookmark by ids removes the membership`() = runTest {
        val collectionId = createCollection("RemoveById")
        val bookmark = collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 20, at(100))

        val removed = collectionBookmarksRepository.removeAyahBookmarkFromCollection(collectionId, bookmark.bookmarkId)

        assertTrue(removed)
        assertTrue(collectionBookmarksRepository.getBookmarksForCollection(collectionId).isEmpty())
    }

    @Test
    fun `removing a collection bookmark that is not a member reports nothing removed`() = runTest {
        val collectionId = createCollection("RemoveMissingMember")
        val otherCollectionId = createCollection("KeepOtherMember")
        val bookmark = collectionBookmarksRepository.addAyahBookmarkToCollection(otherCollectionId, 2, 21, at(100))

        val removed = collectionBookmarksRepository.removeAyahBookmarkFromCollection(collectionId, bookmark.bookmarkId)

        assertFalse(removed)
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(otherCollectionId).size)
    }

    @Test
    fun `removing a collection bookmark twice reports nothing removed the second time`() = runTest {
        val collectionId = createCollection("RemoveTwice")
        val bookmark = collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 22, at(100))
        collectionBookmarksRepository.removeAyahBookmarkFromCollection(collectionId, bookmark.bookmarkId)

        val removedAgain = collectionBookmarksRepository.removeAyahBookmarkFromCollection(collectionId, bookmark.bookmarkId)

        assertFalse(removedAgain)
    }

    @Test
    fun `removing a collection bookmark with an id that names no verse reports nothing removed`() = runTest {
        val collectionId = createCollection("RemoveMalformed")
        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 22, at(100))

        listOf("22", "2:x", "2:22:1", "remote-bookmark").forEach { bookmarkId ->
            assertFalse(collectionBookmarksRepository.removeAyahBookmarkFromCollection(collectionId, bookmarkId))
        }

        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(collectionId).size)
    }

    private suspend fun createCollection(name: String): String = collectionsRepository.addCollection(name, at(50)).id

    private fun memberships(sura: Long, ayah: Long) =
        database.synced.collectionsQueries.collectionAyahsAt(sura, ayah).executeAsList()

    private fun PlatformDateTime.millis() = fromPlatform().toEpochMilliseconds()
}

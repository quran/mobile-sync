package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.held
import com.quran.shared.persistence.markSynced
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.pending
import com.quran.shared.persistence.planPush
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.syncv2.types.syncTypes
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
        val removedBookmark = collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 22, at(100))
        assertTrue(collectionBookmarksRepository.removeAyahBookmarkFromCollection(collectionId, removedBookmark.bookmarkId))

        val removed = collectionBookmarksRepository.removeAyahBookmarkFromCollection(collectionId, bookmark.bookmarkId)
        val removedAgain = collectionBookmarksRepository.removeAyahBookmarkFromCollection(collectionId, removedBookmark.bookmarkId)

        assertFalse(removed)
        assertFalse(removedAgain, "a verse already removed is not a member either")
        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(otherCollectionId).size)
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

    @Test
    fun `removing all bookmarks from a collection empties it and keeps it`() = runTest {
        val study = createCollection("Study")
        val review = createCollection("Review")
        listOf(2 to 255, 3 to 18).forEach { (sura, ayah) ->
            collectionBookmarksRepository.addAyahBookmarkToCollection(study, sura, ayah, at(100))
        }
        collectionBookmarksRepository.addAyahBookmarkToCollection(review, 2, 255, at(200))
        collectionBookmarksRepository.addAyahBookmarkToCollection(favoritesId(), 3, 18, at(200))
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.BLUE, at(300))

        val removed = collectionBookmarksRepository.removeAllAyahBookmarksFromCollection(study)

        assertEquals(2, removed)
        assertEquals(emptyList(), collectionBookmarksRepository.getBookmarksForCollection(study))
        assertTrue(collectionsRepository.getAllCollections().any { it.id == study }, "the collection stays")
        assertEquals(listOf("2:255"), collectionBookmarksRepository.getBookmarksForCollection(review).map { it.bookmarkId })
        assertEquals(listOf("3:18"), collectionBookmarksRepository.getBookmarksForCollection(favoritesId()).map { it.bookmarkId })
        assertEquals(listOf(AyahHighlightColor.BLUE), collectionBookmarksRepository.getHighlightsFlow().first().map { it.color })
    }

    @Test
    fun `removing all bookmarks from Favorites works on the read-only default collection`() = runTest {
        val study = createCollection("Study")
        listOf(2 to 255, 3 to 18, 4 to 1).forEach { (sura, ayah) ->
            collectionBookmarksRepository.addAyahBookmarkToCollection(favoritesId(), sura, ayah, at(100))
        }
        collectionBookmarksRepository.addAyahBookmarkToCollection(study, 3, 18, at(200))

        val removed = collectionBookmarksRepository.removeAllAyahBookmarksFromCollection(favoritesId())

        assertEquals(3, removed)
        assertEquals(emptyList(), collectionBookmarksRepository.getBookmarksForCollection(favoritesId()))
        assertTrue(collectionsRepository.getAllCollections().single { it.isDefault }.isReadOnly, "Favorites stays")
        assertEquals(listOf("3:18"), collectionBookmarksRepository.getBookmarksForCollection(study).map { it.bookmarkId })
    }

    @Test
    fun `removing all bookmarks from a highlight collection is rejected`() = runTest {
        collectionBookmarksRepository.setHighlight(2, 255, AyahHighlightColor.BLUE, at(100))
        val blue = collectionsRepository.getAllCollections().single { it.name == AyahHighlightColor.BLUE.collectionName }

        assertFailsWith<IllegalArgumentException> {
            collectionBookmarksRepository.removeAllAyahBookmarksFromCollection(blue.id)
        }

        assertEquals(listOf(AyahHighlightColor.BLUE), collectionBookmarksRepository.getHighlightsFlow().first().map { it.color })
    }

    @Test
    fun `removing all bookmarks keeps a verse that doesn't exist and doesn't count it`() = runTest {
        val study = createCollection("Study")
        collectionBookmarksRepository.addAyahBookmarkToCollection(study, 2, 999, at(100))
        collectionBookmarksRepository.addAyahBookmarkToCollection(study, 115, 1, at(100))
        collectionBookmarksRepository.addAyahBookmarkToCollection(study, 2, 255, at(100))
        syncTypes(database.synced).forEach(database::markSynced)

        assertEquals(1, collectionBookmarksRepository.removeAllAyahBookmarksFromCollection(study))

        assertEquals(
            setOf("2:999", "115:1"),
            collectionBookmarksRepository.getBookmarksForCollection(study).map { it.bookmarkId }.toSet()
        )
        assertEquals(1, database.pending().size, "only 2:255's DELETE, none the server would refuse")
    }

    @Test
    fun `removing all bookmarks from an empty or unknown collection removes nothing`() = runTest {
        val empty = createCollection("Empty")
        val other = createCollection("Other")
        collectionBookmarksRepository.addAyahBookmarkToCollection(other, 2, 255, at(100))
        syncTypes(database.synced).forEach(database::markSynced)

        listOf(empty, "999", "not-an-id").forEach { collectionId ->
            assertEquals(0, collectionBookmarksRepository.removeAllAyahBookmarksFromCollection(collectionId), collectionId)
        }

        assertEquals(1, collectionBookmarksRepository.getBookmarksForCollection(other).size)
        assertEquals(emptyList(), database.pending(), "nothing to push")
    }

    @Test
    fun `removing all bookmarks leaves the same changes to push as removing each one`() = runTest {
        val bulk = collectedDatabase()
        val single = collectedDatabase()

        assertEquals(3, CollectionBookmarksRepositoryImpl(bulk.synced).removeAllAyahBookmarksFromCollection(bulk.favoritesId()))
        CollectionBookmarksRepositoryImpl(single.synced).apply {
            listOf("2:255", "3:18", "5:5").forEach { assertTrue(removeAyahBookmarkFromCollection(single.favoritesId(), it)) }
        }

        assertEquals(single.pending(), bulk.pending())
        assertEquals(2, bulk.pending().size, "one DELETE per synced membership; the never-pushed 5:5 sends nothing")
        assertEquals(single.held(), bulk.held())
        assertEquals(listOf("INVALID_VERSE"), bulk.held().map { it.reason }, "2:999 stays held")
        assertEquals(
            single.synced.collectionsQueries.collectionAyahsAll().executeAsList(),
            bulk.synced.collectionsQueries.collectionAyahsAll().executeAsList()
        )
    }

    /**
     * Synced: 2:255 and 3:18 in Favorites, 3:18 also in Study and highlighted blue. Then, never pushed: 5:5 in
     * Favorites, and 2:999, which doesn't exist, so its create is held.
     */
    private suspend fun collectedDatabase(): MobileSyncDatabase = createTestDatabase().apply {
        val repository = CollectionBookmarksRepositoryImpl(synced)
        val favorites = favoritesId()
        val study = CollectionsRepositoryImpl(synced).addCollection("Study", at(50)).id
        repository.addAyahBookmarkToCollection(favorites, 2, 255, at(100))
        repository.addAyahBookmarkToCollection(favorites, 3, 18, at(200))
        repository.addAyahBookmarkToCollection(study, 3, 18, at(300))
        repository.setHighlight(3, 18, AyahHighlightColor.BLUE, at(400))
        syncTypes(synced).forEach(::markSynced)
        repository.addAyahBookmarkToCollection(favorites, 5, 5, at(500))
        repository.addAyahBookmarkToCollection(favorites, 2, 999, at(600))
        planPush()
        assertEquals(listOf("INVALID_VERSE"), held().map { it.reason })
    }

    private fun favoritesId(): String = database.favoritesId()

    private fun MobileSyncDatabase.favoritesId(): String =
        synced.collectionsQueries.collectionDefault().executeAsOne().local_id.toString()

    private suspend fun createCollection(name: String): String = collectionsRepository.addCollection(name, at(50)).id

    private fun memberships(sura: Long, ayah: Long) =
        database.synced.collectionsQueries.collectionAyahsAt(sura, ayah).executeAsList()

    private fun PlatformDateTime.millis() = fromPlatform().toEpochMilliseconds()
}

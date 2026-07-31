@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.input.RemoteCollectionBookmark
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsSynchronizationRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksSynchronizationRepository
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class DefaultCollectionRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var bookmarksRepository: BookmarksRepository
    private lateinit var collectionsRepository: CollectionsRepository
    private lateinit var collectionsSyncRepository: CollectionsSynchronizationRepository
    private lateinit var collectionBookmarksRepository: CollectionBookmarksRepository
    private lateinit var collectionBookmarksSyncRepository: CollectionBookmarksSynchronizationRepository

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        bookmarksRepository = BookmarksRepositoryImpl(database)
        collectionsRepository = CollectionsRepositoryImpl(database)
        collectionsSyncRepository = collectionsRepository as CollectionsSynchronizationRepository
        collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(database)
        collectionBookmarksSyncRepository =
            collectionBookmarksRepository as CollectionBookmarksSynchronizationRepository
    }

    @Test
    fun `remote default collection binds unsynced Favorites without losing pending membership`() = runTest {
        val localFavorites = collectionsRepository.addCollection(FAVORITES_NAME)
        val bookmark = bookmarksRepository.addBookmark(42)
        val membership = collectionBookmarksRepository.addBookmarkToCollection(
            localFavorites.localId,
            bookmark
        )

        assertTrue(collectionBookmarksSyncRepository.fetchMutatedCollectionBookmarks().isEmpty())

        collectionsSyncRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultCollectionMutation()),
            localMutationsToClear = emptyList()
        )

        val collections = collectionsRepository.getAllCollections()
        assertEquals(1, collections.size)
        assertEquals(localFavorites.localId, collections.single().localId)
        assertEquals(
            DEFAULT_COLLECTION_ID,
            database.collectionsQueries
                .getCollectionByLocalId(localFavorites.localId.toLong())
                .executeAsOne()
                .remote_id
        )
        assertTrue(collectionsSyncRepository.fetchMutatedCollections().isEmpty())

        val memberships = collectionBookmarksRepository.getBookmarksForCollection(localFavorites.localId)
        assertEquals(listOf(membership.localId), memberships.map { it.localId })
        val pendingMembership = collectionBookmarksSyncRepository
            .fetchMutatedCollectionBookmarks()
            .single()
        assertEquals(Mutation.CREATED, pendingMembership.mutation)
        assertEquals(membership.localId, pendingMembership.localID)
        assertEquals(DEFAULT_COLLECTION_ID, pendingMembership.model.collectionRemoteId)
    }

    @Test
    fun `default collection rename is suppressed without enqueuing a collection mutation`() = runTest {
        persistDefaultCollection()
        val defaultCollection = collectionsRepository.getAllCollections().single()

        val returned = collectionsRepository.updateCollection(defaultCollection.localId, "Renamed")

        assertEquals(FAVORITES_NAME, returned.name)
        assertEquals(FAVORITES_NAME, collectionsRepository.getAllCollections().single().name)
        assertTrue(collectionsSyncRepository.fetchMutatedCollections().isEmpty())
    }

    @Test
    fun `default collection delete is suppressed without removing memberships or enqueuing mutation`() = runTest {
        persistDefaultCollection()
        val defaultCollection = collectionsRepository.getAllCollections().single()
        val bookmark = bookmarksRepository.addBookmark(24)
        val membership = collectionBookmarksRepository.addBookmarkToCollection(
            defaultCollection.localId,
            bookmark
        )

        val deleted = collectionsRepository.deleteCollection(defaultCollection.localId)

        assertFalse(deleted)
        assertEquals(listOf(defaultCollection), collectionsRepository.getAllCollections())
        assertEquals(
            listOf(membership.localId),
            collectionBookmarksRepository
                .getBookmarksForCollection(defaultCollection.localId)
                .map { it.localId }
        )
        assertTrue(collectionsSyncRepository.fetchMutatedCollections().isEmpty())
    }

    @Test
    fun `remote default membership without its collection is rejected without side effects`() = runTest {
        collectionBookmarksSyncRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultMembershipMutation("remote-membership-1")),
            localMutationsToClear = emptyList()
        )

        assertTrue(database.bookmark_collectionsQueries.getCollectionBookmarks().executeAsList().isEmpty())
        assertTrue(bookmarksRepository.getAllBookmarks().isEmpty())
        assertTrue(collectionBookmarksSyncRepository.fetchMutatedCollectionBookmarks().isEmpty())
    }

    @Test
    fun `default membership add remove and re-add round trips remain syncable`() = runTest {
        persistDefaultCollection()
        val defaultCollection = collectionsRepository.getAllCollections().single()
        val bookmark = bookmarksRepository.addBookmark(42)

        collectionBookmarksRepository.addBookmarkToCollection(defaultCollection.localId, bookmark)
        val pendingCreate = collectionBookmarksSyncRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, pendingCreate.mutation)
        assertEquals(DEFAULT_COLLECTION_ID, pendingCreate.model.collectionRemoteId)
        assertNull(pendingCreate.remoteID)

        collectionBookmarksSyncRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultMembershipMutation("remote-membership-1")),
            localMutationsToClear = listOf(pendingCreate)
        )
        assertTrue(collectionBookmarksSyncRepository.fetchMutatedCollectionBookmarks().isEmpty())
        assertEquals(
            1,
            collectionBookmarksRepository.getBookmarksForCollection(defaultCollection.localId).size
        )

        collectionBookmarksRepository.removeBookmarkFromCollection(defaultCollection.localId, bookmark)
        assertTrue(
            collectionBookmarksRepository.getBookmarksForCollection(defaultCollection.localId).isEmpty()
        )
        val pendingDelete = collectionBookmarksSyncRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, pendingDelete.mutation)
        assertEquals(DEFAULT_COLLECTION_ID, pendingDelete.model.collectionRemoteId)
        assertEquals("remote-membership-1", pendingDelete.remoteID)

        collectionBookmarksSyncRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                defaultMembershipMutation(
                    remoteId = "remote-membership-1",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = listOf(pendingDelete)
        )
        assertTrue(collectionBookmarksSyncRepository.fetchMutatedCollectionBookmarks().isEmpty())

        collectionBookmarksRepository.addBookmarkToCollection(defaultCollection.localId, bookmark)
        val pendingReAdd = collectionBookmarksSyncRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, pendingReAdd.mutation)
        assertEquals(DEFAULT_COLLECTION_ID, pendingReAdd.model.collectionRemoteId)
        assertNull(pendingReAdd.remoteID)

        collectionBookmarksSyncRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultMembershipMutation("remote-membership-2")),
            localMutationsToClear = listOf(pendingReAdd)
        )
        assertTrue(collectionBookmarksSyncRepository.fetchMutatedCollectionBookmarks().isEmpty())
        assertEquals(
            1,
            collectionBookmarksRepository.getBookmarksForCollection(defaultCollection.localId).size
        )
    }

    private suspend fun persistDefaultCollection() {
        collectionsSyncRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultCollectionMutation()),
            localMutationsToClear = emptyList()
        )
    }

    private fun defaultCollectionMutation() = RemoteModelMutation(
        model = RemoteCollection(
            name = FAVORITES_NAME,
            lastUpdated = Instant.fromEpochMilliseconds(1_000).toPlatform()
        ),
        remoteID = DEFAULT_COLLECTION_ID,
        mutation = Mutation.CREATED
    )

    private fun defaultMembershipMutation(
        remoteId: String,
        mutation: Mutation = Mutation.CREATED
    ): RemoteModelMutation<RemoteCollectionBookmark> = RemoteModelMutation(
        model = RemoteCollectionBookmark.Page(
            collectionId = DEFAULT_COLLECTION_ID,
            page = 42,
            lastUpdated = Instant.fromEpochMilliseconds(2_000).toPlatform(),
            bookmarkId = "bookmark-42"
        ),
        remoteID = remoteId,
        mutation = mutation
    )

    private companion object {
        const val DEFAULT_COLLECTION_ID = "__default__"
        const val FAVORITES_NAME = "Favorites"
    }
}

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.RemoteCollectionBookmark
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CollectionBookmarksRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var bookmarksRepository: BookmarksRepositoryImpl
    private lateinit var repository: CollectionBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        bookmarksRepository = BookmarksRepositoryImpl(database)
        repository = CollectionBookmarksRepositoryImpl(database)
    }

    @Test
    fun `addBookmarkToCollection stores an ayah bookmark`() = runTest {
        database.collectionsQueries.addNewCollection(name = "Recents")
        val collection = database.collectionsQueries.getCollectionByName("Recents").executeAsOne()
        val collectionRemoteId = "remote-collection-id"
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = collectionRemoteId,
            name = collection.name,
            modified_at = 1L,
            local_id = collection.local_id
        )

        val bookmark = bookmarksRepository.addBookmark(2, 255)
        repository.addBookmarkToCollection(collection.local_id.toString(), bookmark)

        val records = database.bookmark_collectionsQueries
            .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collection.local_id)
            .executeAsList()

        assertEquals(1, records.size)
        assertEquals("AYAH", records.single().bookmark_type)
        assertEquals(bookmark.localId, records.single().bookmark_local_id)
        assertEquals(2L, records.single().sura)
        assertEquals(255L, records.single().ayah)
    }

    @Test
    fun `addAyahBookmarkToCollection atomically stores bookmark and link`() = runTest {
        database.collectionsQueries.addNewCollection(name = "AtomicRecents")
        val collection = database.collectionsQueries.getCollectionByName("AtomicRecents").executeAsOne()
        val collectionRemoteId = "remote-atomic-collection-id"
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = collectionRemoteId,
            name = collection.name,
            modified_at = 1L,
            local_id = collection.local_id
        )

        repository.addAyahBookmarkToCollection(
            collectionLocalId = collection.local_id.toString(),
            sura = 5,
            ayah = 6
        )

        val bookmark = database.ayah_bookmarksQueries.getBookmarkForAyah(5L, 6L).executeAsOneOrNull()
        assertNotNull(bookmark)

        val records = database.bookmark_collectionsQueries
            .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collection.local_id)
            .executeAsList()

        assertEquals(1, records.size)
        assertEquals(bookmark.local_id.toString(), records.single().bookmark_local_id)
        assertEquals(5L, records.single().sura)
        assertEquals(6L, records.single().ayah)
    }

    @Test
    fun `addAyahBookmarkToCollection rolls back bookmark when collection is missing`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            repository.addAyahBookmarkToCollection(
                collectionLocalId = "999999",
                sura = 7,
                ayah = 8
            )
        }

        assertNull(
            database.ayah_bookmarksQueries.getBookmarkForAyah(7L, 8L).executeAsOneOrNull(),
            "Bookmark insert should be rolled back when collection linking fails"
        )
    }

    @Test
    fun `applyRemoteChanges persists remote ayah collection bookmark`() = runTest {
        database.collectionsQueries.addNewCollection(name = "Recents")
        val collection = database.collectionsQueries.getCollectionByName("Recents").executeAsOne()
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = "remote-collection-id",
            name = collection.name,
            modified_at = 1L,
            local_id = collection.local_id
        )

        val bookmark = bookmarksRepository.addBookmark(2, 255)

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollectionBookmark.Ayah(
                        collectionId = "remote-collection-id",
                        sura = 2,
                        ayah = 255,
                        lastUpdated = Instant.fromEpochMilliseconds(100L).toPlatform(),
                        bookmarkId = "remote-collection-bookmark-id"
                    ),
                    remoteID = "remote-collection-bookmark-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val records = database.bookmark_collectionsQueries
            .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collection.local_id)
            .executeAsList()

        assertEquals(1, records.size)
        assertEquals("remote-collection-bookmark-id", records.single().remote_id)
        assertEquals("AYAH", records.single().bookmark_type)
        assertEquals(bookmark.localId, records.single().bookmark_local_id)
    }

    @Test
    fun `applyRemoteChanges backfills remote bookmark id for existing local ayah bookmark`() = runTest {
        database.collectionsQueries.addNewCollection(name = "BackfillRemoteBookmarkId")
        val collection = database.collectionsQueries.getCollectionByName("BackfillRemoteBookmarkId").executeAsOne()
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = "remote-collection-id-backfill",
            name = collection.name,
            modified_at = 1L,
            local_id = collection.local_id
        )

        val localBookmark = bookmarksRepository.addBookmark(4, 4)

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollectionBookmark.Ayah(
                        collectionId = "remote-collection-id-backfill",
                        sura = 4,
                        ayah = 4,
                        lastUpdated = Instant.fromEpochMilliseconds(300L).toPlatform(),
                        bookmarkId = "remote-bookmark-id-backfill"
                    ),
                    remoteID = "remote-collection-bookmark-id-backfill",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val bookmark = database.ayah_bookmarksQueries.getBookmarkForAyah(4L, 4L).executeAsOneOrNull()
        assertNotNull(bookmark)
        assertEquals("remote-bookmark-id-backfill", bookmark.remote_id)
        assertEquals(localBookmark.localId.toLong(), bookmark.local_id)

        val records = database.bookmark_collectionsQueries
            .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collection.local_id)
            .executeAsList()
        assertEquals(1, records.size)
        assertEquals("remote-bookmark-id-backfill", records.single().bookmark_remote_id)
    }

    @Test
    fun `applyRemoteChanges creates missing local ayah bookmark for collection link`() = runTest {
        database.collectionsQueries.addNewCollection(name = "NeedBookmark")
        val collection = database.collectionsQueries.getCollectionByName("NeedBookmark").executeAsOne()
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = "remote-collection-id-2",
            name = collection.name,
            modified_at = 1L,
            local_id = collection.local_id
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollectionBookmark.Ayah(
                        collectionId = "remote-collection-id-2",
                        sura = 3,
                        ayah = 2,
                        lastUpdated = Instant.fromEpochMilliseconds(200L).toPlatform(),
                        bookmarkId = "remote-collection-bookmark-id-2"
                    ),
                    remoteID = "remote-collection-bookmark-id-2",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val bookmark = database.ayah_bookmarksQueries.getBookmarkForAyah(3L, 2L).executeAsOneOrNull()
        assertTrue(bookmark != null, "Missing ayah bookmark should be inserted before linking collection bookmark")
        assertEquals(1, database.bookmark_collectionsQueries
            .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collection.local_id)
            .executeAsList().size)
    }

    @Test
    fun `fetchMutatedCollectionBookmarks skips links without remote bookmark ids`() = runTest {
        database.collectionsQueries.addNewCollection(name = "NeedsBookmarkRemoteId")
        val collection = database.collectionsQueries.getCollectionByName("NeedsBookmarkRemoteId").executeAsOne()
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = "remote-collection-id-4",
            name = collection.name,
            modified_at = 1L,
            local_id = collection.local_id
        )

        val bookmark = bookmarksRepository.addBookmark(1, 1)
        repository.addBookmarkToCollection(collection.local_id.toString(), bookmark)

        val mutations = repository.fetchMutatedCollectionBookmarks()

        assertTrue(
            mutations.isEmpty(),
            "Collection bookmarks should not sync until the linked bookmark has a remote id"
        )
    }

    @Test
    fun `applyRemoteChanges ignores remote page collection bookmarks`() = runTest {
        database.collectionsQueries.addNewCollection(name = "IgnorePages")
        val collection = database.collectionsQueries.getCollectionByName("IgnorePages").executeAsOne()
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = "remote-collection-id-3",
            name = collection.name,
            modified_at = 1L,
            local_id = collection.local_id
        )

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollectionBookmark.Page(
                        collectionId = "remote-collection-id-3",
                        page = 200,
                        lastUpdated = Instant.fromEpochMilliseconds(300L).toPlatform()
                    ),
                    remoteID = "remote-collection-bookmark-id-3",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        assertTrue(
            database.bookmark_collectionsQueries
                .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collection.local_id)
                .executeAsList().isEmpty(),
            "Page collection bookmarks should be ignored"
        )
        assertNull(database.ayah_bookmarksQueries.getBookmarkForAyah(200L, 1L).executeAsOneOrNull())
    }
}

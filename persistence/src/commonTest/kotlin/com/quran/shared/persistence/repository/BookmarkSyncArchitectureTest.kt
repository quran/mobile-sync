@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.ImportAyahBookmark
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.input.RemoteCollectionBookmark
import com.quran.shared.persistence.model.DEFAULT_COLLECTION_ID
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class BookmarkSyncArchitectureTest {
    private lateinit var database: QuranDatabase
    private lateinit var bookmarksRepository: BookmarksRepositoryImpl
    private lateinit var readingRepository: ReadingBookmarksRepositoryImpl
    private lateinit var collectionsRepository: CollectionsRepositoryImpl
    private lateinit var collectionBookmarksRepository: CollectionBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        bookmarksRepository = BookmarksRepositoryImpl(database)
        readingRepository = ReadingBookmarksRepositoryImpl(database)
        collectionsRepository = CollectionsRepositoryImpl(database)
        collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(database)
    }

    @Test
    fun `addBookmark creates default virtual membership`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(2, 255)

        val row = database.bookmarksQueries.getBookmarkForAyah(2L, 255L).executeAsOne()
        assertEquals(bookmark.localId.toLong(), row.local_id)
        assertEquals(1L, row.is_in_default_collection)
        assertEquals("CREATED", row.default_pending_op)
        assertEquals(0L, database.bookmark_collectionsQueries.countAll().executeAsOne())
    }

    @Test
    fun `addBookmark with custom collection creates custom membership only`() = runTest {
        val collectionId = createCollection("Custom", "remote-custom")

        bookmarksRepository.addBookmark(2, 1, listOf(collectionId))

        val row = database.bookmarksQueries.getBookmarkForAyah(2L, 1L).executeAsOne()
        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(row.local_id, collectionId.toLong())
            .executeAsOne()
        assertEquals(0L, row.is_in_default_collection)
        assertEquals(1L, link.is_active)
        assertEquals("CREATED", link.pending_op)
    }

    @Test
    fun `addBookmark supports default and custom membership together`() = runTest {
        val collectionId = createCollection("Both", "remote-both")

        bookmarksRepository.addBookmark(2, 2, listOf(DEFAULT_COLLECTION_ID, collectionId))

        val row = database.bookmarksQueries.getBookmarkForAyah(2L, 2L).executeAsOne()
        assertEquals(1L, row.is_in_default_collection)
        assertEquals(1L, database.bookmark_collectionsQueries.countActiveForBookmark(row.local_id).executeAsOne())
    }

    @Test
    fun `addBookmark with custom collection preserves existing default membership`() = runTest {
        val collectionId = createCollection("AdditiveCustom", "remote-additive-custom")
        bookmarksRepository.addBookmark(2, 5)

        bookmarksRepository.addBookmark(2, 5, listOf(collectionId))

        val row = database.bookmarksQueries.getBookmarkForAyah(2L, 5L).executeAsOne()
        assertEquals(1L, row.is_in_default_collection)
        assertEquals(1L, database.bookmark_collectionsQueries.countActiveForBookmark(row.local_id).executeAsOne())
    }

    @Test
    fun `empty collection input normalizes to default membership`() = runTest {
        bookmarksRepository.addBookmark(2, 3, emptyList())

        val row = database.bookmarksQueries.getBookmarkForAyah(2L, 3L).executeAsOne()
        assertEquals(1L, row.is_in_default_collection)
    }

    @Test
    fun `remove default-only local ayah bookmark prunes it`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(2, 4)

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)

        assertNull(database.bookmarksQueries.getBookmarkForAyah(2L, 4L).executeAsOneOrNull())
    }

    @Test
    fun `add page reading bookmark stores reading facet`() = runTest {
        val bookmark = readingRepository.addPageReadingBookmark(42)

        val row = database.bookmarksQueries.getBookmarkForPage(42L).executeAsOne()
        assertEquals(bookmark.page.toLong(), row.page)
        assertEquals(1L, row.is_reading)
    }

    @Test
    fun `reading ayah bookmark can later join default collection`() = runTest {
        readingRepository.addAyahReadingBookmark(3, 2)
        bookmarksRepository.addBookmark(3, 2)

        val row = database.bookmarksQueries.getBookmarkForAyah(3L, 2L).executeAsOne()
        assertEquals(1L, row.is_reading)
        assertEquals(1L, row.is_in_default_collection)
    }

    @Test
    fun `reading ayah bookmark can join default and custom collection`() = runTest {
        val collectionId = createCollection("ReadAndSaved", "remote-read-saved")
        readingRepository.addAyahReadingBookmark(3, 3)

        bookmarksRepository.addBookmark(3, 3, listOf(DEFAULT_COLLECTION_ID, collectionId))

        val row = database.bookmarksQueries.getBookmarkForAyah(3L, 3L).executeAsOne()
        assertEquals(1L, row.is_reading)
        assertEquals(1L, row.is_in_default_collection)
        assertEquals(1L, database.bookmark_collectionsQueries.countActiveForBookmark(row.local_id).executeAsOne())
    }

    @Test
    fun `remove custom collection preserves reading and default facets`() = runTest {
        val collectionId = createCollection("RemoveCustom", "remote-remove-custom")
        readingRepository.addAyahReadingBookmark(3, 4)
        val bookmark = bookmarksRepository.addBookmark(3, 4, listOf(DEFAULT_COLLECTION_ID, collectionId))

        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)

        val row = database.bookmarksQueries.getBookmarkForAyah(3L, 4L).executeAsOne()
        assertEquals(1L, row.is_reading)
        assertEquals(1L, row.is_in_default_collection)
        assertEquals(0L, database.bookmark_collectionsQueries.countActiveForBookmark(row.local_id).executeAsOne())
    }

    @Test
    fun `remove reading facet preserves default bookmark`() = runTest {
        readingRepository.addAyahReadingBookmark(3, 5)
        bookmarksRepository.addBookmark(3, 5)

        assertTrue(readingRepository.deleteReadingBookmark())

        val row = database.bookmarksQueries.getBookmarkForAyah(3L, 5L).executeAsOne()
        assertEquals(0L, row.is_reading)
        assertEquals(1L, row.is_in_default_collection)
    }

    @Test
    fun `re-adding remote-backed reading bookmark clears stale full-row delete`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(3, 6, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-reading-3-6",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        assertTrue(readingRepository.deleteReadingBookmark())
        readingRepository.addAyahReadingBookmark(3, 6)

        val row = database.bookmarksQueries.getBookmarkForAyah(3L, 6L).executeAsOne()
        assertEquals(1L, row.is_reading)
        assertNull(row.bookmark_pending_op)
        assertTrue(bookmarksRepository.fetchMutatedBookmarks().none { it.mutation == Mutation.DELETED })
    }

    @Test
    fun `clearing losing local reading mutation preserves newer remote reading timestamp`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(3, 7, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-reading-3-7",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(3, 7, "remote-reading-3-7", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )
        val remoteWonRow = database.bookmarksQueries.getBookmarkForAyah(3L, 7L).executeAsOne()
        database.bookmarksQueries.clearReadingBookmark(local_id = remoteWonRow.local_id, timestamp = 150L)
        readingRepository.addAyahReadingBookmark(3, 8, at(175))
        val losingLocalMutation = bookmarksRepository.fetchMutatedBookmarks().single {
            it.localID == remoteWonRow.local_id.toString()
        }

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(3, 7, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-reading-3-7",
                    mutation = Mutation.MODIFIED
                )
            ),
            localMutationsToClear = listOf(losingLocalMutation)
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(3L, 7L).executeAsOne()
        assertEquals(1L, row.is_reading)
        assertEquals(200L, row.reading_modified_at)
        assertNull(row.reading_pending_op)
        assertEquals(row.local_id.toString(), readingRepository.getReadingBookmark()?.localId)
    }

    @Test
    fun `clearing stale local delete preserves remote bookmark recreation`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(3, 9, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-reading-recreate-old",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        assertTrue(readingRepository.deleteReadingBookmark())
        val staleLocalDelete = bookmarksRepository.fetchMutatedBookmarks().single {
            it.remoteID == "remote-reading-recreate-old"
        }

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(3, 9, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-reading-recreate-new",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(staleLocalDelete)
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(3L, 9L).executeAsOne()
        assertEquals("remote-reading-recreate-new", row.remote_id)
        assertEquals(1L, row.is_reading)
        assertNull(row.bookmark_pending_op)
    }

    @Test
    fun `default collection create ack backfills bookmark remote id and clears default pending`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(4, 1)
        val localMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 1, "remote-bookmark-4-1", Mutation.CREATED)),
            localMutationsToClear = listOf(localMutation)
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(4L, 1L).executeAsOne()
        assertEquals(bookmark.localId.toLong(), row.local_id)
        assertEquals("remote-bookmark-4-1", row.remote_id)
        assertNull(row.default_pending_op)
    }

    @Test
    fun `remote default delete clears default but keeps reading facet`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(4, 2, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-bookmark-4-2",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 2, "remote-bookmark-4-2", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 2, "remote-bookmark-4-2", Mutation.DELETED)),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-4-2").executeAsOne()
        assertEquals(1L, row.is_reading)
        assertEquals(0L, row.is_in_default_collection)
    }

    @Test
    fun `remote default delete prunes otherwise orphaned bookmark`() = runTest {
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 3, "remote-bookmark-4-3", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 3, "remote-bookmark-4-3", Mutation.DELETED)),
            localMutationsToClear = emptyList()
        )

        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-4-3").executeAsOneOrNull())
    }

    @Test
    fun `removing unacknowledged default membership cancels pending create`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(4, 16, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-bookmark-default-cancel",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        bookmarksRepository.addBookmark(4, 16, listOf(DEFAULT_COLLECTION_ID))
        val bookmark = bookmarksRepository.getAllBookmarks().single { it.sura == 4 && it.ayah == 16 }

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-default-cancel").executeAsOne()
        assertEquals(0L, row.is_in_default_collection)
        assertNull(row.default_pending_op)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none {
            it.model.collectionRemoteId == DEFAULT_COLLECTION_ID &&
                it.model.bookmarkRemoteId == "remote-bookmark-default-cancel"
        })
    }

    @Test
    fun `delete readd delete of synced default membership still emits delete`() = runTest {
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 20, "remote-bookmark-default-toggle", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )
        val bookmark = bookmarksRepository.getAllBookmarks().single { it.sura == 4 && it.ayah == 20 }

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)
        bookmarksRepository.addBookmark(4, 20, listOf(DEFAULT_COLLECTION_ID))
        val restoredRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-default-toggle").executeAsOne()
        assertEquals(1L, restoredRow.is_in_default_collection)
        assertNull(restoredRow.default_pending_op)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none {
            it.model.collectionRemoteId == DEFAULT_COLLECTION_ID &&
                it.model.bookmarkRemoteId == "remote-bookmark-default-toggle"
        })

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-default-toggle").executeAsOne()
        assertEquals(0L, row.is_in_default_collection)
        assertEquals("DELETED", row.default_pending_op)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single {
            it.model.collectionRemoteId == DEFAULT_COLLECTION_ID &&
                it.model.bookmarkRemoteId == "remote-bookmark-default-toggle"
        }
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
    }

    @Test
    fun `clearing pushed default delete after readd queues recreation`() = runTest {
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 23, "remote-bookmark-default-readd-clear", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )
        val bookmark = bookmarksRepository.getAllBookmarks().single { it.sura == 4 && it.ayah == 23 }

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single {
            it.model.collectionRemoteId == DEFAULT_COLLECTION_ID
        }
        bookmarksRepository.addBookmark(4, 23, listOf(DEFAULT_COLLECTION_ID))

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(deleteMutation)
        )

        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single {
            it.model.collectionRemoteId == DEFAULT_COLLECTION_ID
        }
        assertEquals(Mutation.CREATED, createMutation.mutation)
        assertEquals("remote-bookmark-default-readd-clear", createMutation.model.bookmarkRemoteId)
    }

    @Test
    fun `default collection acknowledgement does not overwrite existing bookmark remote id`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(4, 21, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-bookmark-default-current",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        bookmarksRepository.addBookmark(4, 21, listOf(DEFAULT_COLLECTION_ID))
        val localMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single {
            it.model.collectionRemoteId == DEFAULT_COLLECTION_ID
        }
        val mismatchedAck = LocalModelMutation(
            model = localMutation.model.copy(bookmarkRemoteId = "remote-bookmark-default-other"),
            remoteID = localMutation.remoteID,
            localID = localMutation.localID,
            mutation = localMutation.mutation
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(mismatchedAck)
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(4L, 21L).executeAsOne()
        assertEquals("remote-bookmark-default-current", row.remote_id)
        assertNull(row.default_pending_op)
    }

    @Test
    fun `readding active synced default membership does not create duplicate pending relation`() = runTest {
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 22, "remote-bookmark-default-active", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )

        bookmarksRepository.addBookmark(4, 22, listOf(DEFAULT_COLLECTION_ID))

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-default-active").executeAsOne()
        assertEquals(1L, row.is_in_default_collection)
        assertNull(row.default_pending_op)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none {
            it.model.collectionRemoteId == DEFAULT_COLLECTION_ID &&
                it.model.bookmarkRemoteId == "remote-bookmark-default-active"
        })
    }

    @Test
    fun `default relation lookup by remote id resolves virtual membership only while active`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(4, 13, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-bookmark-4-13",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 13, "remote-bookmark-4-13", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )

        val remoteId = "$DEFAULT_COLLECTION_ID-remote-bookmark-4-13"
        val activeRelation = assertNotNull(collectionBookmarksRepository.fetchCollectionBookmarkByRemoteId(remoteId))
        assertEquals(DEFAULT_COLLECTION_ID, activeRelation.collectionRemoteId)
        assertEquals("remote-bookmark-4-13", activeRelation.bookmarkRemoteId)

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 13, "remote-bookmark-4-13", Mutation.DELETED)),
            localMutationsToClear = emptyList()
        )

        assertNull(collectionBookmarksRepository.fetchCollectionBookmarkByRemoteId(remoteId))
    }

    @Test
    fun `default relation lookup by remote id resolves pending local tombstone`() = runTest {
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 17, "remote-bookmark-4-17", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )

        val bookmark = bookmarksRepository.getAllBookmarks().single()
        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)

        val remoteId = "$DEFAULT_COLLECTION_ID-remote-bookmark-4-17"
        val tombstone = assertNotNull(collectionBookmarksRepository.fetchCollectionBookmarkByRemoteId(remoteId))
        assertEquals(DEFAULT_COLLECTION_ID, tombstone.collectionRemoteId)
        assertEquals("remote-bookmark-4-17", tombstone.bookmarkRemoteId)
    }

    @Test
    fun `acknowledged default link delete does not restore stale bookmark remote id`() = runTest {
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 15, "remote-bookmark-old-default-delete", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )

        val bookmark = bookmarksRepository.getAllBookmarks().single()
        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(4, 15, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-bookmark-new-default-delete",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(deleteMutation)
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(4L, 15L).executeAsOne()
        assertEquals("remote-bookmark-new-default-delete", row.remote_id)
        assertNull(row.default_pending_op)
    }

    @Test
    fun `deleting collection keeps synced custom links pending deletion`() = runTest {
        val collectionId = createCollection("DeleteCollection", "remote-delete-collection")
        bookmarksRepository.addBookmark(4, 4, listOf(collectionId))
        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-delete-collection",
                    sura = 4,
                    ayah = 4,
                    bookmarkId = "remote-bookmark-4-4",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(createMutation)
        )

        assertTrue(collectionsRepository.deleteCollection(collectionId))

        val deletion = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, deletion.mutation)
        assertEquals("remote-delete-collection", deletion.model.collectionRemoteId)
        assertEquals("remote-bookmark-4-4", deletion.model.bookmarkRemoteId)
    }

    @Test
    fun `acknowledged custom link delete prunes otherwise orphaned bookmark`() = runTest {
        val collectionId = createCollection("DeleteOnlyCustom", "remote-delete-only-custom")
        bookmarksRepository.addBookmark(4, 5, listOf(collectionId))
        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-delete-only-custom",
                    sura = 4,
                    ayah = 5,
                    bookmarkId = "remote-bookmark-4-5",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(createMutation)
        )

        val bookmark = bookmarksRepository.getAllBookmarks().single()
        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-delete-only-custom",
                    sura = 4,
                    ayah = 5,
                    bookmarkId = "remote-bookmark-4-5",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = listOf(deleteMutation)
        )

        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-4-5").executeAsOneOrNull())
        assertNull(database.bookmarksQueries.getBookmarkForAyah(4L, 5L).executeAsOneOrNull())
    }

    @Test
    fun `acknowledged custom link delete does not restore stale bookmark remote id`() = runTest {
        val collectionId = createCollection("DeleteStaleBookmarkId", "remote-delete-stale-bookmark-id")
        bookmarksRepository.addBookmark(4, 14, listOf(collectionId))
        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-delete-stale-bookmark-id",
                    sura = 4,
                    ayah = 14,
                    bookmarkId = "remote-bookmark-old-delete",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(createMutation)
        )

        val bookmark = bookmarksRepository.getAllBookmarks().single()
        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(4, 14, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-bookmark-new-delete",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(deleteMutation)
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(4L, 14L).executeAsOne()
        assertEquals("remote-bookmark-new-delete", row.remote_id)
    }

    @Test
    fun `stale bookmark id in collection link does not recreate default relation`() = runTest {
        createCollection("DefaultRemoteIdReplacement", "remote-default-replacement-custom")
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 18, "remote-bookmark-old-default-replace", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-default-replacement-custom",
                    sura = 4,
                    ayah = 18,
                    bookmarkId = "remote-bookmark-old-default-replace",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-default-replacement-custom",
                    sura = 4,
                    ayah = 18,
                    bookmarkId = "remote-bookmark-new-default-replace",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(4L, 18L).executeAsOne()
        assertEquals("remote-bookmark-old-default-replace", row.remote_id)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none {
            it.model.bookmarkRemoteId == "remote-bookmark-new-default-replace"
        })
    }

    @Test
    fun `remote default relation create reactivates deleted bookmark row`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(4, 6, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-bookmark-4-6",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        assertTrue(readingRepository.deleteReadingBookmark())

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 6, "remote-bookmark-4-6", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-4-6").executeAsOne()
        assertEquals(0L, row.deleted)
        assertEquals(1L, row.is_in_default_collection)
        assertNull(row.bookmark_pending_op)
    }

    @Test
    fun `remote custom relation create reactivates deleted bookmark row`() = runTest {
        val collectionId = createCollection("ReactivateCustom", "remote-reactivate-custom")
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(4, 7, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-bookmark-4-7",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        assertTrue(readingRepository.deleteReadingBookmark())

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-reactivate-custom",
                    sura = 4,
                    ayah = 7,
                    bookmarkId = "remote-bookmark-4-7",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-4-7").executeAsOne()
        assertEquals(0L, row.deleted)
        assertNull(row.bookmark_pending_op)
        assertEquals(1L, database.bookmark_collectionsQueries.countActiveForBookmark(row.local_id).executeAsOne())
        assertEquals(collectionId, database.bookmark_collectionsQueries.getCollectionBookmarks().executeAsOne().collection_local_id.toString())
    }

    @Test
    fun `stale remote relation delete does not restore old bookmark remote id`() = runTest {
        createCollection("StaleDelete", "remote-stale-delete")
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(4, 8, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-bookmark-new",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-stale-delete",
                    sura = 4,
                    ayah = 8,
                    bookmarkId = "remote-bookmark-old",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(4L, 8L).executeAsOne()
        assertEquals("remote-bookmark-new", row.remote_id)
    }

    @Test
    fun `remote delete for old relation id preserves active recreated link`() = runTest {
        val collectionId = createCollection("RecreatePreserve", "remote-old-recreate")
        val bookmark = bookmarksRepository.addBookmark(4, 10, listOf(collectionId))
        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-old-recreate",
                    sura = 4,
                    ayah = 10,
                    bookmarkId = "remote-bookmark-4-10",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(createMutation)
        )
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = "remote-new-recreate",
            name = "RecreatePreserve",
            modified_at = 200L,
            local_id = collectionId.toLong()
        )
        collectionBookmarksRepository.addBookmarkToCollection(collectionId, bookmark)

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-old-recreate",
                    sura = 4,
                    ayah = 10,
                    bookmarkId = "remote-bookmark-4-10",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val recreated = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, recreated.mutation)
        assertEquals("remote-new-recreate", recreated.model.collectionRemoteId)
        assertEquals("remote-bookmark-4-10", recreated.model.bookmarkRemoteId)
        val row = database.bookmarksQueries.getBookmarkForAyah(4L, 10L).executeAsOne()
        assertEquals(1L, database.bookmark_collectionsQueries.countActiveForBookmark(row.local_id).executeAsOne())
    }

    @Test
    fun `readding synced custom relation cancels pending delete`() = runTest {
        val collectionId = createCollection("ReaddCustom", "remote-readd-custom")
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-readd-custom",
                    sura = 4,
                    ayah = 14,
                    bookmarkId = "remote-bookmark-readd-custom",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val bookmark = bookmarksRepository.getAllBookmarks().single { it.sura == 4 && it.ayah == 14 }

        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        assertEquals(Mutation.DELETED, collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single().mutation)

        collectionBookmarksRepository.addBookmarkToCollection(collectionId, bookmark)

        val row = database.bookmarksQueries.getBookmarkForAyah(4L, 14L).executeAsOne()
        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(row.local_id, collectionId.toLong())
            .executeAsOne()
        assertEquals(1L, link.is_active)
        assertNull(link.pending_op)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `clearing pushed custom delete after readd queues recreation`() = runTest {
        val collectionId = createCollection("ReaddCustomClear", "remote-readd-custom-clear")
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-readd-custom-clear",
                    sura = 4,
                    ayah = 17,
                    bookmarkId = "remote-bookmark-readd-custom-clear",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val bookmark = bookmarksRepository.getAllBookmarks().single { it.sura == 4 && it.ayah == 17 }

        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.addBookmarkToCollection(collectionId, bookmark)

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(deleteMutation)
        )

        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, createMutation.mutation)
        assertEquals("remote-readd-custom-clear", createMutation.model.collectionRemoteId)
        assertEquals("remote-bookmark-readd-custom-clear", createMutation.model.bookmarkRemoteId)
    }

    @Test
    fun `remote custom delete removes same-key local pending create without bookmark remote id`() = runTest {
        val collectionId = createCollection("RemoteDeleteWins", "remote-delete-wins")
        bookmarksRepository.addBookmark(4, 15, listOf(collectionId))
        val localMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertNull(localMutation.model.bookmarkRemoteId)

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-delete-wins",
                    sura = 4,
                    ayah = 15,
                    bookmarkId = "remote-bookmark-delete-wins",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = listOf(localMutation)
        )

        assertEquals(0L, database.bookmark_collectionsQueries.countAll().executeAsOne())
        assertNull(database.bookmarksQueries.getBookmarkForAyah(4L, 15L).executeAsOneOrNull())
    }

    @Test
    fun `stale relation lookup returns snapshot ids for null data delete`() = runTest {
        val collectionId = createCollection("NullDataStaleDelete", "remote-old-null-delete")
        val bookmark = bookmarksRepository.addBookmark(4, 19, listOf(collectionId))
        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-old-null-delete",
                    sura = 4,
                    ayah = 19,
                    bookmarkId = "remote-bookmark-4-19",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(createMutation)
        )
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = "remote-new-null-delete",
            name = "NullDataStaleDelete",
            modified_at = 200L,
            local_id = collectionId.toLong()
        )
        collectionBookmarksRepository.addBookmarkToCollection(collectionId, bookmark)

        val oldRemoteId = "remote-old-null-delete-remote-bookmark-4-19"
        val staleRelation = assertNotNull(collectionBookmarksRepository.fetchCollectionBookmarkByRemoteId(oldRemoteId))
        assertEquals("remote-old-null-delete", staleRelation.collectionRemoteId)
        assertEquals("remote-bookmark-4-19", staleRelation.bookmarkRemoteId)

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = requireNotNull(staleRelation.collectionRemoteId),
                    sura = staleRelation.sura,
                    ayah = staleRelation.ayah,
                    bookmarkId = requireNotNull(staleRelation.bookmarkRemoteId),
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val recreated = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, recreated.mutation)
        assertEquals("remote-new-null-delete", recreated.model.collectionRemoteId)
        assertEquals("remote-bookmark-4-19", recreated.model.bookmarkRemoteId)
    }

    @Test
    fun `relation existence includes active link with current parent remote ids`() = runTest {
        val collectionId = createCollection("CurrentRemoteExistence", "remote-current-collection")
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(4, 11, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-current-bookmark",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 4, 11)

        val remoteId = "remote-current-collection-remote-current-bookmark"
        assertEquals(true, collectionBookmarksRepository.remoteResourcesExist(listOf(remoteId)).getValue(remoteId))
    }

    @Test
    fun `remote collection link ignores bookmark id for a different ayah`() = runTest {
        createCollection("MismatchedBookmarkId", "remote-mismatched-bookmark-id")
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 4, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-bookmark-mismatch",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-mismatched-bookmark-id",
                    sura = 5,
                    ayah = 5,
                    bookmarkId = "remote-bookmark-mismatch",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val originalRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-mismatch").executeAsOne()
        assertEquals(5L, originalRow.sura)
        assertEquals(4L, originalRow.ayah)
        assertEquals(0L, database.bookmark_collectionsQueries.countAll().executeAsOne())
        assertNull(database.bookmarksQueries.getBookmarkForAyah(5L, 5L).executeAsOneOrNull())
    }

    @Test
    fun `stale remote collection link does not overwrite newer bookmark remote id`() = runTest {
        createCollection("StaleLinkBookmarkId", "remote-stale-link-bookmark-id")
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 6, isReading = false, lastUpdated = at(200)),
                    remoteID = "remote-bookmark-current-link",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-stale-link-bookmark-id",
                    sura = 5,
                    ayah = 6,
                    bookmarkId = "remote-bookmark-stale-link",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(5L, 6L).executeAsOne()
        assertEquals("remote-bookmark-current-link", row.remote_id)
        assertEquals(0L, database.bookmark_collectionsQueries.countAll().executeAsOne())
    }

    @Test
    fun `remote bookmark create ignores existing remote id for a different ayah`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 10, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-bookmark-direct-mismatch",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 11, isReading = false, lastUpdated = at(200)),
                    remoteID = "remote-bookmark-direct-mismatch",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val originalRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-direct-mismatch").executeAsOne()
        assertEquals(5L, originalRow.sura)
        assertEquals(10L, originalRow.ayah)
        assertNull(database.bookmarksQueries.getBookmarkForAyah(5L, 11L).executeAsOneOrNull())
    }

    @Test
    fun `remote bookmark create does not overwrite target row when remote id belongs to another ayah`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 12, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-bookmark-direct-original",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 13, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-bookmark-direct-target",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 13, isReading = false, lastUpdated = at(200)),
                    remoteID = "remote-bookmark-direct-original",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val originalRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-direct-original").executeAsOne()
        val targetRow = database.bookmarksQueries.getBookmarkForAyah(5L, 13L).executeAsOne()
        assertEquals(5L, originalRow.sura)
        assertEquals(12L, originalRow.ayah)
        assertEquals("remote-bookmark-direct-target", targetRow.remote_id)
    }

    @Test
    fun `pushed bookmark ack replaces stale local remote id before fetched stale row persists`() = runTest {
        readingRepository.addAyahReadingBookmark(5, 17)
        database.bookmarksQueries.upsertAyahBookmark(
            remote_id = "remote-reading-stale",
            ayah_id = 1L,
            sura = 5L,
            ayah = 17L,
            timestamp = 100L
        )
        val localMutation = bookmarksRepository.fetchMutatedBookmarks().single {
            it.remoteID == "remote-reading-stale"
        }

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 17, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-reading-new",
                    mutation = Mutation.CREATED
                ),
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 18, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-reading-stale",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(localMutation)
        )

        val readingRow = database.bookmarksQueries.getBookmarkForAyah(5L, 17L).executeAsOne()
        val staleRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-reading-stale").executeAsOne()
        assertEquals("remote-reading-new", readingRow.remote_id)
        assertEquals(1L, readingRow.is_reading)
        assertNull(readingRow.reading_pending_op)
        assertEquals(5L, staleRow.sura)
        assertEquals(18L, staleRow.ayah)
    }

    @Test
    fun `remote bookmark delete leaves custom relation tombstone fetchable`() = runTest {
        val collectionId = createCollection("BookmarkDeleteRelation", "remote-bookmark-delete-collection")
        bookmarksRepository.addBookmark(4, 12, listOf(collectionId))
        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-bookmark-delete-collection",
                    sura = 4,
                    ayah = 12,
                    bookmarkId = "remote-bookmark-4-12",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(createMutation)
        )

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(4, 12, isReading = false, lastUpdated = at(200)),
                    remoteID = "remote-bookmark-4-12",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val relationDelete = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, relationDelete.mutation)
        assertEquals("remote-bookmark-delete-collection", relationDelete.model.collectionRemoteId)
        assertEquals("remote-bookmark-4-12", relationDelete.model.bookmarkRemoteId)

        val deletedBookmark = database.bookmarksQueries.getBookmarkForAyah(4L, 12L).executeAsOne()
        assertEquals(1L, deletedBookmark.deleted)
        assertNull(deletedBookmark.remote_id)
    }

    @Test
    fun `remote default delete ignores bookmark id for a different ayah`() = runTest {
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(5, 8, "remote-default-delete-mismatch", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(5, 9, "remote-default-delete-mismatch", Mutation.DELETED)),
            localMutationsToClear = emptyList()
        )

        val originalRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-default-delete-mismatch").executeAsOne()
        assertEquals(5L, originalRow.sura)
        assertEquals(8L, originalRow.ayah)
        assertEquals(1L, originalRow.is_in_default_collection)
        assertNull(database.bookmarksQueries.getBookmarkForAyah(5L, 9L).executeAsOneOrNull())
    }

    @Test
    fun `remote custom delete ignores bookmark id for a different ayah`() = runTest {
        val collectionId = createCollection("CustomDeleteMismatch", "remote-custom-delete-mismatch")
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 15, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-custom-delete-bookmark",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-custom-delete-mismatch",
                    sura = 5,
                    ayah = 15,
                    bookmarkId = "remote-custom-delete-bookmark",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-custom-delete-mismatch",
                    sura = 5,
                    ayah = 16,
                    bookmarkId = "remote-custom-delete-bookmark",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val bookmarkRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-custom-delete-bookmark").executeAsOne()
        assertEquals(5L, bookmarkRow.sura)
        assertEquals(15L, bookmarkRow.ayah)
        assertEquals(1L, database.bookmark_collectionsQueries.countActiveForBookmark(bookmarkRow.local_id).executeAsOne())
        assertEquals(
            collectionId.toLong(),
            database.bookmark_collectionsQueries
                .getCollectionBookmarkFor(bookmarkRow.local_id, collectionId.toLong())
                .executeAsOne()
                .collection_local_id
        )
    }

    @Test
    fun `remote collection delete prunes custom-only bookmark orphan`() = runTest {
        val collectionId = createCollection("RemoteDeleteCollection", "remote-delete-collection-with-bookmark")
        bookmarksRepository.addBookmark(4, 9, listOf(collectionId))
        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-delete-collection-with-bookmark",
                    sura = 4,
                    ayah = 9,
                    bookmarkId = "remote-bookmark-4-9",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(createMutation)
        )

        collectionsRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollection("RemoteDeleteCollection", at(200)),
                    remoteID = "remote-delete-collection-with-bookmark",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = emptyList()
        )

        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-4-9").executeAsOneOrNull())
        assertNull(database.bookmarksQueries.getBookmarkForAyah(4L, 9L).executeAsOneOrNull())
    }

    @Test
    fun `collection remote id replacement marks active link for recreation`() = runTest {
        val collectionId = createCollection("Replacement", "remote-old-collection")
        val bookmark = bookmarksRepository.addBookmark(5, 1, listOf(collectionId))
        val localMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollectionBookmark.Ayah(
                        collectionId = "remote-old-collection",
                        sura = 5,
                        ayah = 1,
                        lastUpdated = at(100),
                        bookmarkId = "remote-bookmark-5-1"
                    ),
                    remoteID = "remote-old-collection-remote-bookmark-5-1",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(localMutation)
        )

        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = "remote-new-collection",
            name = "Replacement",
            modified_at = 200L,
            local_id = collectionId.toLong()
        )
        collectionBookmarksRepository.addBookmarkToCollection(collectionId, bookmark)

        val recreated = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, recreated.mutation)
        assertEquals("remote-new-collection", recreated.model.collectionRemoteId)
        assertEquals("remote-bookmark-5-1", recreated.model.bookmarkRemoteId)
    }

    @Test
    fun `stale bookmark id in collection link does not recreate other active links`() = runTest {
        val firstCollectionId = createCollection("First", "remote-first-collection")
        val secondCollectionId = createCollection("Second", "remote-second-collection")
        bookmarksRepository.addBookmark(5, 2, listOf(firstCollectionId, secondCollectionId))
        val localMutations = collectionBookmarksRepository.fetchMutatedCollectionBookmarks()

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-first-collection",
                    sura = 5,
                    ayah = 2,
                    bookmarkId = "remote-bookmark-old",
                    mutation = Mutation.CREATED
                ),
                customRemoteMutation(
                    collectionId = "remote-second-collection",
                    sura = 5,
                    ayah = 2,
                    bookmarkId = "remote-bookmark-old",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = localMutations
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-first-collection",
                    sura = 5,
                    ayah = 2,
                    bookmarkId = "remote-bookmark-new",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(5L, 2L).executeAsOne()
        assertEquals("remote-bookmark-old", row.remote_id)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none {
            it.model.bookmarkRemoteId == "remote-bookmark-new"
        })
    }

    @Test
    fun `remote reading changes respect latest timestamp singleton`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(6, 1, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-reading-old",
                    mutation = Mutation.CREATED
                ),
                RemoteModelMutation(
                    model = RemoteBookmark.Page(77, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-reading-new",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val reading = readingRepository.getReadingBookmark() as PageReadingBookmark
        assertEquals(77, reading.page)
        assertEquals(0L, database.bookmarksQueries.getBookmarkByRemoteId("remote-reading-old").executeAsOne().is_reading)
    }

    @Test
    fun `replacing remote reading-only bookmark tombstones old bookmark`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(6, 2, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-reading-only-old",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        readingRepository.addAyahReadingBookmark(6, 3)

        val oldRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-reading-only-old").executeAsOne()
        assertEquals(1L, oldRow.deleted)
        assertEquals("DELETED", oldRow.bookmark_pending_op)
        assertNull(oldRow.reading_pending_op)
        val deleteMutation = bookmarksRepository.fetchMutatedBookmarks().single {
            it.remoteID == "remote-reading-only-old"
        }
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
    }

    @Test
    fun `full reading bookmark delete exports fresh delete timestamp`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(6, 7, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-reading-delete-timestamp",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        assertTrue(readingRepository.deleteReadingBookmark())

        val deleteMutation = bookmarksRepository.fetchMutatedBookmarks().single {
            it.remoteID == "remote-reading-delete-timestamp"
        }
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertTrue(deleteMutation.model.lastUpdated.fromPlatform().toEpochMilliseconds() > 100L)
    }

    @Test
    fun `local deletes without explicit timestamp advance modified timestamps`() = runTest {
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(6, 4, "remote-default-timestamp", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )
        val defaultBookmark = bookmarksRepository.getAllBookmarks().single { it.sura == 6 && it.ayah == 4 }

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, defaultBookmark)

        val defaultRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-default-timestamp").executeAsOne()
        assertTrue(defaultRow.modified_at > 100L)
        assertTrue(requireNotNull(defaultRow.default_modified_at) > 100L)

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(6, 5, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-reading-timestamp",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(6, 5, "remote-reading-timestamp", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )

        readingRepository.deleteReadingBookmark()

        val readingRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-reading-timestamp").executeAsOne()
        assertTrue(readingRow.modified_at > 100L)
        assertTrue(requireNotNull(readingRow.reading_modified_at) > 100L)

        val collectionId = createCollection("TimestampCustom", "remote-timestamp-custom")
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-timestamp-custom",
                    sura = 6,
                    ayah = 6,
                    bookmarkId = "remote-custom-timestamp",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val customBookmark = bookmarksRepository.getAllBookmarks().single { it.sura == 6 && it.ayah == 6 }

        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, customBookmark)

        val customRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-custom-timestamp").executeAsOne()
        val customLink = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(customRow.local_id, collectionId.toLong())
            .executeAsOne()
        assertTrue(customLink.modified_at > 100L)
    }

    @Test
    fun `import stores bookmarks in canonical default facet and custom links`() = runTest {
        val importRepository = PersistenceImportRepositoryImpl(database)
        importRepository.importData(
            PersistenceImportData(
                bookmarks = listOf(ImportAyahBookmark("b1", 7, 1, at(100))),
                collections = listOf(ImportCollection("c1", "Imported", at(100))),
                collectionBookmarks = listOf(ImportCollectionAyahBookmark("c1", "b1", at(100)))
            ),
            deleteExisting = false
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(7L, 1L).executeAsOne()
        assertEquals(1L, row.is_in_default_collection)
        assertEquals(1L, database.bookmark_collectionsQueries.countActiveForBookmark(row.local_id).executeAsOne())
    }

    @Test
    fun `imported reading bookmark tombstones displaced remote reading-only bookmark`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(7, 2, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-import-reading-old",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val importRepository = PersistenceImportRepositoryImpl(database)
        importRepository.importData(
            PersistenceImportData(
                readingBookmark = ImportReadingBookmark.Ayah(7, 3, at(200))
            ),
            deleteExisting = false
        )

        val oldRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-import-reading-old").executeAsOne()
        assertEquals(1L, oldRow.deleted)
        assertEquals("DELETED", oldRow.bookmark_pending_op)
        val deleteMutation = bookmarksRepository.fetchMutatedBookmarks().single {
            it.remoteID == "remote-import-reading-old"
        }
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
    }

    private fun createCollection(name: String, remoteId: String): String {
        database.collectionsQueries.addNewCollection(name = name, timestamp = null)
        val collection = database.collectionsQueries.getCollectionByName(name).executeAsOne()
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = remoteId,
            name = name,
            modified_at = 1L,
            local_id = collection.local_id
        )
        return collection.local_id.toString()
    }

    private fun defaultRemoteMutation(
        sura: Int,
        ayah: Int,
        bookmarkId: String,
        mutation: Mutation
    ): RemoteModelMutation<RemoteCollectionBookmark> {
        return RemoteModelMutation(
            model = RemoteCollectionBookmark.Ayah(
                collectionId = DEFAULT_COLLECTION_ID,
                sura = sura,
                ayah = ayah,
                lastUpdated = at(100),
                bookmarkId = bookmarkId
            ),
            remoteID = "$DEFAULT_COLLECTION_ID-$bookmarkId",
            mutation = mutation
        )
    }

    private fun customRemoteMutation(
        collectionId: String,
        sura: Int,
        ayah: Int,
        bookmarkId: String,
        mutation: Mutation
    ): RemoteModelMutation<RemoteCollectionBookmark> {
        return RemoteModelMutation(
            model = RemoteCollectionBookmark.Ayah(
                collectionId = collectionId,
                sura = sura,
                ayah = ayah,
                lastUpdated = at(100),
                bookmarkId = bookmarkId
            ),
            remoteID = "$collectionId-$bookmarkId",
            mutation = mutation
        )
    }

    private fun at(timestamp: Long) = Instant.fromEpochMilliseconds(timestamp).toPlatform()
}

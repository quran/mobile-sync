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
import kotlin.test.assertFailsWith
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
    fun `applyRemoteChanges checks write boundary before bookmark transaction`() = runTest {
        assertFailsWith<IllegalStateException> {
            bookmarksRepository.applyRemoteChanges(
                updatesToPersist = listOf(
                    RemoteModelMutation(
                        model = RemoteBookmark.Ayah(
                            sura = 2,
                            ayah = 255,
                            isReading = false,
                            lastUpdated = Instant.fromEpochMilliseconds(2000L).toPlatform()
                        ),
                        remoteID = "remote-bookmark-id",
                        mutation = Mutation.CREATED
                    )
                ),
                localMutationsToClear = emptyList(),
                writeBoundaryGuard = PersistenceWriteBoundaryGuard {
                    throw IllegalStateException("stale epoch")
                }
            )
        }

        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-id").executeAsOneOrNull())
    }

    @Test
    fun `applyRemoteChanges checks write boundary before collection bookmark transaction`() = runTest {
        assertFailsWith<IllegalStateException> {
            collectionBookmarksRepository.applyRemoteChanges(
                updatesToPersist = listOf(
                    RemoteModelMutation(
                        model = RemoteCollectionBookmark.Ayah(
                            collectionId = "remote-collection-id",
                            sura = 2,
                            ayah = 255,
                            lastUpdated = Instant.fromEpochMilliseconds(2000L).toPlatform(),
                            bookmarkId = "remote-bookmark-id"
                        ),
                        remoteID = "remote-collection-bookmark-id",
                        mutation = Mutation.CREATED
                    )
                ),
                localMutationsToClear = emptyList(),
                writeBoundaryGuard = PersistenceWriteBoundaryGuard {
                    throw IllegalStateException("stale epoch")
                }
            )
        }

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
    fun `clearing stale local delete does not retarget same-location bookmark remote id`() = runTest {
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

        assertNull(database.bookmarksQueries.getBookmarkForAyah(3L, 9L).executeAsOneOrNull())
        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-reading-recreate-old").executeAsOneOrNull())
        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-reading-recreate-new").executeAsOneOrNull())
    }

    @Test
    fun `remote bookmark id already owned by pending different location is not moved`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(3, 11, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-stable-bookmark-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val original = database.bookmarksQueries.getBookmarkForAyah(3L, 11L).executeAsOne()
        database.bookmarksQueries.clearReadingBookmark(local_id = original.local_id, timestamp = 150L)

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(3, 12, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-stable-bookmark-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val retained = database.bookmarksQueries.getBookmarkByRemoteId("remote-stable-bookmark-id").executeAsOne()
        assertEquals(original.local_id, retained.local_id)
        assertEquals(3L, retained.sura)
        assertEquals(11L, retained.ayah)
        assertNotNull(retained.reading_pending_op)
        assertNull(database.bookmarksQueries.getBookmarkForAyah(3L, 12L).executeAsOneOrNull())
    }

    @Test
    fun `default collection create ack backfills bookmark remote id and clears default pending`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(4, 1)
        val localMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        val provenAck = LocalModelMutation(
            model = localMutation.model.copy(bookmarkRemoteId = "remote-bookmark-4-1"),
            remoteID = "$DEFAULT_COLLECTION_ID-remote-bookmark-4-1",
            localID = localMutation.localID,
            mutation = localMutation.mutation,
            ack = localMutation.ack
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 1, "remote-bookmark-4-1", Mutation.CREATED)),
            localMutationsToClear = listOf(provenAck)
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(4L, 1L).executeAsOne()
        assertEquals(bookmark.localId.toLong(), row.local_id)
        assertEquals("remote-bookmark-4-1", row.remote_id)
        assertNull(row.default_pending_op)
    }

    @Test
    fun `default collection create ack without proven parent remote id stores relation snapshot only`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(4, 21)
        val localMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        val unprovenRelationAck = LocalModelMutation(
            model = localMutation.model.copy(bookmarkRemoteId = null),
            remoteID = "$DEFAULT_COLLECTION_ID-remote-unproven-default-parent",
            localID = localMutation.localID,
            mutation = localMutation.mutation,
            ack = localMutation.ack
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(unprovenRelationAck)
        )

        val row = database.bookmarksQueries.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOne()
        assertNull(row.remote_id)
        assertNull(row.default_pending_op)
        assertNotNull(
            collectionBookmarksRepository.fetchCollectionBookmarkByRemoteId(
                "$DEFAULT_COLLECTION_ID-remote-unproven-default-parent"
            )
        )
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `custom collection create ack without proven parent id stores relation snapshot only`() = runTest {
        val collectionId = createCollection("UnprovenCustomParent", "remote-unproven-custom")
        val bookmark = bookmarksRepository.addBookmark(4, 22, listOf(collectionId))
        val localMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        val unprovenRelationAck = LocalModelMutation(
            model = localMutation.model.copy(bookmarkRemoteId = null),
            remoteID = "remote-unproven-custom-remote-unproven-custom-parent",
            localID = localMutation.localID,
            mutation = localMutation.mutation,
            ack = localMutation.ack
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(unprovenRelationAck)
        )

        val bookmarkRow = database.bookmarksQueries.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOne()
        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(localMutation.localID.toLong())
            .executeAsOne()
        assertNull(bookmarkRow.remote_id)
        assertNull(link.pending_op)
        assertEquals("remote-unproven-custom-parent", link.last_synced_bookmark_remote_id)
        assertEquals("remote-unproven-custom", link.last_synced_collection_remote_id)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `custom create ack pending delete without proven parent id binds relation snapshot only`() = runTest {
        val collectionId = createCollection("UnprovenCustomPendingDelete", "remote-unproven-pending-delete")
        val bookmark = bookmarksRepository.addBookmark(4, 23, listOf(collectionId))
        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(createMutation.ack)))
        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        val unprovenRelationAck = LocalModelMutation(
            model = createMutation.model.copy(bookmarkRemoteId = null),
            remoteID = "remote-unproven-pending-delete-remote-unproven-delete-parent",
            localID = createMutation.localID,
            mutation = createMutation.mutation,
            ack = createMutation.ack
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(unprovenRelationAck)
        )

        val bookmarkRow = database.bookmarksQueries.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOne()
        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(createMutation.localID.toLong())
            .executeAsOne()
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertNull(bookmarkRow.remote_id)
        assertEquals(0L, link.is_active)
        assertEquals("DELETED", link.pending_op)
        assertEquals("remote-unproven-delete-parent", link.last_synced_bookmark_remote_id)
        assertEquals("remote-unproven-pending-delete", link.last_synced_collection_remote_id)
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-unproven-delete-parent", deleteMutation.model.bookmarkRemoteId)
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
            mutation = localMutation.mutation,
            ack = localMutation.ack
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

        assertNull(database.bookmarksQueries.getBookmarkForAyah(4L, 15L).executeAsOneOrNull())
        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-old-default-delete").executeAsOneOrNull())
        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-new-default-delete").executeAsOneOrNull())
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
        assertEquals("remote-bookmark-old-delete", row.remote_id)
        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-new-delete").executeAsOneOrNull())
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
    fun `remote default relation create preserves parent bookmark pending delete`() = runTest {
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
        val pendingDelete = bookmarksRepository.fetchMutatedBookmarks().single {
            it.remoteID == "remote-bookmark-4-6"
        }
        assertEquals(Mutation.DELETED, pendingDelete.mutation)

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(4, 6, "remote-bookmark-4-6", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-4-6").executeAsOne()
        assertEquals(1L, row.deleted)
        assertEquals(0L, row.is_in_default_collection)
        assertEquals("DELETED", row.bookmark_pending_op)
        val retainedDelete = bookmarksRepository.fetchMutatedBookmarks().single {
            it.remoteID == "remote-bookmark-4-6"
        }
        assertEquals(Mutation.DELETED, retainedDelete.mutation)
    }

    @Test
    fun `remote custom relation create preserves parent bookmark pending delete`() = runTest {
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
        val pendingDelete = bookmarksRepository.fetchMutatedBookmarks().single {
            it.remoteID == "remote-bookmark-4-7"
        }
        assertEquals(Mutation.DELETED, pendingDelete.mutation)

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
        assertEquals(1L, row.deleted)
        assertEquals("DELETED", row.bookmark_pending_op)
        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(row.local_id, collectionId.toLong())
            .executeAsOne()
        assertEquals(0L, link.is_active)
        assertEquals("DELETED", link.pending_op)
        assertEquals("remote-bookmark-4-7", link.last_synced_bookmark_remote_id)
        assertEquals("remote-reactivate-custom", link.last_synced_collection_remote_id)
        val retainedDelete = bookmarksRepository.fetchMutatedBookmarks().single {
            it.remoteID == "remote-bookmark-4-7"
        }
        assertEquals(Mutation.DELETED, retainedDelete.mutation)
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
    fun `remote bookmark create does not overwrite existing ayah remote id at same location`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 13, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-ayah-old",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 13, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-ayah-new",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(5L, 13L).executeAsOne()
        assertEquals("remote-ayah-old", row.remote_id)
        assertEquals(0L, row.is_reading)
        assertEquals(100L, row.modified_at)
        assertEquals(100L, row.bookmark_modified_at)
        assertEquals(100L, row.reading_modified_at)
        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-ayah-new").executeAsOneOrNull())
    }

    @Test
    fun `remote bookmark create does not overwrite pending same-location ayah remote id`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 16, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-ayah-pending-old",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        readingRepository.addAyahReadingBookmark(5, 16, at(150))
        val pending = bookmarksRepository.fetchMutatedBookmarks().single()
        assertEquals("remote-ayah-pending-old", pending.remoteID)

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 16, isReading = false, lastUpdated = at(200)),
                    remoteID = "remote-ayah-pending-new",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(5L, 16L).executeAsOne()
        val remaining = bookmarksRepository.fetchMutatedBookmarks().single()
        assertEquals("remote-ayah-pending-old", row.remote_id)
        assertEquals(1L, row.is_reading)
        assertEquals("CREATED", row.reading_pending_op)
        assertEquals("remote-ayah-pending-old", remaining.remoteID)
        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-ayah-pending-new").executeAsOneOrNull())
    }

    @Test
    fun `remote bookmark create does not overwrite same-location remote id with pending default facet`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 17, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-ayah-default-old",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        bookmarksRepository.addBookmark(5, 17, listOf(DEFAULT_COLLECTION_ID), at(150))
        val rowWithPendingDefault = database.bookmarksQueries.getBookmarkForAyah(5L, 17L).executeAsOne()
        assertEquals("CREATED", rowWithPendingDefault.default_pending_op)

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 17, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-ayah-default-new",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(5L, 17L).executeAsOne()
        assertEquals("remote-ayah-default-old", row.remote_id)
        assertEquals("CREATED", row.default_pending_op)
        assertEquals(0L, row.is_reading)
        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-ayah-default-new").executeAsOneOrNull())
    }

    @Test
    fun `remote bookmark create does not overwrite same-location remote id with pending entity delete`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 18, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-ayah-delete-old",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        assertTrue(readingRepository.deleteReadingBookmark())
        val rowWithPendingDelete = database.bookmarksQueries.getBookmarkForAyah(5L, 18L).executeAsOne()
        assertEquals("DELETED", rowWithPendingDelete.bookmark_pending_op)

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 18, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-ayah-delete-new",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(5L, 18L).executeAsOne()
        assertEquals("remote-ayah-delete-old", row.remote_id)
        assertEquals("DELETED", row.bookmark_pending_op)
        assertEquals(1L, row.deleted)
        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-ayah-delete-new").executeAsOneOrNull())
    }

    @Test
    fun `remote bookmark create does not overwrite existing page remote id at same location`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Page(88, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-page-old",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Page(88, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-page-new",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkForPage(88L).executeAsOne()
        assertEquals("remote-page-old", row.remote_id)
        assertEquals(0L, row.is_reading)
        assertEquals(100L, row.modified_at)
        assertEquals(100L, row.bookmark_modified_at)
        assertEquals(100L, row.reading_modified_at)
        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-page-new").executeAsOneOrNull())
    }

    @Test
    fun `remote bookmark create backfills null remote id at same ayah location`() = runTest {
        bookmarksRepository.addBookmark(5, 14, at(100))

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(5, 14, isReading = false, lastUpdated = at(200)),
                    remoteID = "remote-ayah-backfill",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(5L, 14L).executeAsOne()
        assertEquals("remote-ayah-backfill", row.remote_id)
        assertEquals("remote-ayah-backfill", database.bookmarksQueries.getBookmarkByRemoteId("remote-ayah-backfill").executeAsOne().remote_id)
    }

    @Test
    fun `remote bookmark create does not replace pending reading remote id before fetched stale row persists`() = runTest {
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
        assertEquals("remote-reading-stale", readingRow.remote_id)
        assertEquals(1L, readingRow.is_reading)
        assertNull(readingRow.reading_pending_op)
        assertEquals(5L, staleRow.sura)
        assertEquals(17L, staleRow.ayah)
        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-reading-new").executeAsOneOrNull())
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

    @Test
    fun `stale bookmark entity ACK does not clear readded reading bookmark`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(8, 1, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-stale-entity",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        assertTrue(readingRepository.deleteReadingBookmark())
        val staleDelete = bookmarksRepository.fetchMutatedBookmarks().single()

        readingRepository.addAyahReadingBookmark(8, 1, at(200))
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(staleDelete)
        )

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-stale-entity").executeAsOne()
        assertEquals(0L, row.deleted)
        assertEquals(1L, row.is_reading)
        assertNull(row.bookmark_pending_op)
        assertEquals(Mutation.CREATED, bookmarksRepository.fetchMutatedBookmarks().single().mutation)
    }

    @Test
    fun `pushed bookmark delete ACK does not apply stale remote delete after readd`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(8, 11, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-stale-delete-ack",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        assertTrue(readingRepository.deleteReadingBookmark())
        val staleDelete = bookmarksRepository.fetchMutatedBookmarks().single()

        readingRepository.addAyahReadingBookmark(8, 11, at(200))
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(8, 11, isReading = true, lastUpdated = at(150)),
                    remoteID = "remote-stale-delete-ack",
                    mutation = Mutation.DELETED,
                    ack = staleDelete.ack
                )
            ),
            localMutationsToClear = listOf(staleDelete)
        )

        val rowAfterAck = database.bookmarksQueries.getBookmarkByRemoteId("remote-stale-delete-ack").executeAsOne()
        assertEquals(0L, rowAfterAck.deleted)
        assertEquals(1L, rowAfterAck.is_reading)
        assertEquals("CREATED", rowAfterAck.reading_pending_op)

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(8, 11, isReading = true, lastUpdated = at(250)),
                    remoteID = "remote-stale-delete-ack",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = emptyList()
        )

        assertNull(database.bookmarksQueries.getBookmarkByRemoteId("remote-stale-delete-ack").executeAsOneOrNull())
        assertNull(database.bookmarksQueries.getBookmarkForAyah(8L, 11L).executeAsOneOrNull())
    }

    @Test
    fun `stale reading ACK does not clear newer reading facet write`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(8, 2, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-stale-reading",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(8, 2, "remote-stale-reading", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )
        val bookmark = bookmarksRepository.getAllBookmarks().single()
        assertTrue(readingRepository.deleteReadingBookmark())
        val staleReadingClear = bookmarksRepository.fetchMutatedBookmarks().single()

        readingRepository.addAyahReadingBookmark(8, 2, at(200))
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(staleReadingClear)
        )

        val row = database.bookmarksQueries.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOne()
        assertEquals(1L, row.is_reading)
        assertEquals("CREATED", row.reading_pending_op)
        assertEquals(Mutation.CREATED, bookmarksRepository.fetchMutatedBookmarks().single().mutation)
    }

    @Test
    fun `stale default ACK does not erase default readd`() = runTest {
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(defaultRemoteMutation(8, 3, "remote-stale-default", Mutation.CREATED)),
            localMutationsToClear = emptyList()
        )
        val bookmark = bookmarksRepository.getAllBookmarks().single()
        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)
        val staleDefaultDelete = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()

        bookmarksRepository.addBookmark(8, 3, listOf(DEFAULT_COLLECTION_ID))
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(staleDefaultDelete)
        )

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-stale-default").executeAsOne()
        assertEquals(1L, row.is_in_default_collection)
        assertEquals("CREATED", row.default_pending_op)
        assertEquals(Mutation.CREATED, collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single().mutation)
    }

    @Test
    fun `stale custom collection link ACK does not erase custom readd`() = runTest {
        val collectionId = createCollection("StaleCustomAck", "remote-stale-custom")
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-stale-custom",
                    sura = 8,
                    ayah = 4,
                    bookmarkId = "remote-stale-custom-bookmark",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val bookmark = bookmarksRepository.getAllBookmarks().single()
        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        val staleCustomDelete = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()

        collectionBookmarksRepository.addBookmarkToCollection(collectionId, bookmark)
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(staleCustomDelete)
        )

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-stale-custom-bookmark").executeAsOne()
        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(row.local_id, collectionId.toLong())
            .executeAsOne()
        assertEquals(1L, link.is_active)
        assertEquals("CREATED", link.pending_op)
        assertEquals(Mutation.CREATED, collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single().mutation)
    }

    @Test
    fun `bookmark create ACK backfills remote id without clearing newer default facet`() = runTest {
        readingRepository.addAyahReadingBookmark(8, 5, at(100))
        val staleBookmarkCreate = bookmarksRepository.fetchMutatedBookmarks().single()
        bookmarksRepository.addBookmark(8, 5, listOf(DEFAULT_COLLECTION_ID), at(200))

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(8, 5, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-created-bookmark-ack",
                    mutation = Mutation.CREATED,
                    ack = staleBookmarkCreate.ack
                )
            ),
            localMutationsToClear = listOf(staleBookmarkCreate)
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(8L, 5L).executeAsOne()
        assertEquals("remote-created-bookmark-ack", row.remote_id)
        assertEquals(1L, row.is_in_default_collection)
        assertEquals("CREATED", row.default_pending_op)
        val remaining = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, remaining.mutation)
        assertEquals("remote-created-bookmark-ack", remaining.model.bookmarkRemoteId)
    }

    @Test
    fun `bookmark create ACK does not restore stale reading state`() = runTest {
        bookmarksRepository.addBookmark(8, 6, listOf(DEFAULT_COLLECTION_ID), at(50))
        readingRepository.addAyahReadingBookmark(8, 6, at(100))
        val staleReadingCreate = bookmarksRepository.fetchMutatedBookmarks().single()

        assertTrue(readingRepository.deleteReadingBookmark())
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(8, 6, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-stale-reading-create-ack",
                    mutation = Mutation.CREATED,
                    ack = staleReadingCreate.ack
                )
            ),
            localMutationsToClear = listOf(staleReadingCreate)
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(8L, 6L).executeAsOne()
        assertEquals("remote-stale-reading-create-ack", row.remote_id)
        assertEquals(0L, row.is_reading)
        assertEquals("CREATED", row.reading_pending_op)
        assertEquals(1L, row.is_in_default_collection)
        val followUp = bookmarksRepository.fetchMutatedBookmarks().single()
        assertEquals(Mutation.CREATED, followUp.mutation)
        assertEquals("remote-stale-reading-create-ack", followUp.remoteID)
        assertEquals(false, followUp.model.isReading)
    }

    @Test
    fun `bookmark create ACK does not backfill or upsert mismatched canonical row`() = runTest {
        bookmarksRepository.addBookmark(8, 7, listOf(DEFAULT_COLLECTION_ID), at(50))
        readingRepository.addAyahReadingBookmark(8, 7, at(100))
        val staleReadingCreate = bookmarksRepository.fetchMutatedBookmarks().single()

        assertTrue(readingRepository.deleteReadingBookmark())
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(8, 8, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-mismatched-reading-create-ack",
                    mutation = Mutation.CREATED,
                    ack = staleReadingCreate.ack
                )
            ),
            localMutationsToClear = listOf(staleReadingCreate)
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(8L, 7L).executeAsOne()
        assertNull(row.remote_id)
        assertNull(database.bookmarksQueries.getBookmarkForAyah(8L, 8L).executeAsOneOrNull())
    }

    @Test
    fun `bookmark entity delete ACK retains row while custom link tombstone is pending`() = runTest {
        val collectionId = createCollection("EntityDeleteAckLink", "remote-entity-delete-link")
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-entity-delete-link",
                    sura = 8,
                    ayah = 9,
                    bookmarkId = "remote-entity-delete-bookmark",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val bookmark = bookmarksRepository.getAllBookmarks().single()
        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        val bookmarkRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-entity-delete-bookmark").executeAsOne()
        database.bookmarksQueries.markBookmarkDeleted(local_id = bookmarkRow.local_id, timestamp = 200L)
        val entityDelete = bookmarksRepository.fetchMutatedBookmarks().single {
            it.remoteID == "remote-entity-delete-bookmark"
        }

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(entityDelete)
        )

        val retainedBookmark = database.bookmarksQueries
            .getBookmarkByLocalId(bookmarkRow.local_id)
            .executeAsOneOrNull()
        assertNotNull(retainedBookmark)
        assertEquals(1L, retainedBookmark.deleted)
        assertNull(retainedBookmark.bookmark_pending_op)
        val linkDelete = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, linkDelete.mutation)
        assertEquals("remote-entity-delete-link", linkDelete.model.collectionRemoteId)
        assertEquals("remote-entity-delete-bookmark", linkDelete.model.bookmarkRemoteId)
    }

    @Test
    fun `custom link delete ACK clear retains snapshot evidence and requires matching version`() = runTest {
        val collectionId = createCollection("CustomDeleteAckSnapshot", "remote-custom-delete-ack-snapshot")
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-custom-delete-ack-snapshot",
                    sura = 8,
                    ayah = 10,
                    bookmarkId = "remote-bookmark-delete-ack-snapshot",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val bookmark = bookmarksRepository.getAllBookmarks().single()
        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        val deleteAck = requireNotNull(deleteMutation.ack)
        val staleVersionDelete = LocalModelMutation(
            model = deleteMutation.model,
            remoteID = deleteMutation.remoteID,
            localID = deleteMutation.localID,
            mutation = deleteMutation.mutation,
            ack = deleteAck.copy(observedPendingVersion = deleteAck.observedPendingVersion + 1)
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(staleVersionDelete)
        )

        assertEquals(Mutation.DELETED, collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single().mutation)

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(deleteMutation)
        )

        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(deleteMutation.localID.toLong())
            .executeAsOne()
        assertEquals(0L, link.is_active)
        assertNull(link.pending_op)
        assertEquals("remote-custom-delete-ack-snapshot", link.last_synced_collection_remote_id)
        assertEquals("remote-bookmark-delete-ack-snapshot", link.last_synced_bookmark_remote_id)
        val remoteId = "remote-custom-delete-ack-snapshot-remote-bookmark-delete-ack-snapshot"
        val retainedSnapshot = assertNotNull(collectionBookmarksRepository.fetchCollectionBookmarkByRemoteId(remoteId))
        assertEquals("remote-custom-delete-ack-snapshot", retainedSnapshot.collectionRemoteId)
        assertEquals("remote-bookmark-delete-ack-snapshot", retainedSnapshot.bookmarkRemoteId)
    }

    @Test
    fun `readding ACK-deleted custom link queues fresh create despite retained snapshots`() = runTest {
        val collectionId = createCollection("CustomReaddAfterAckDelete", "remote-custom-readd-after-ack")
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-custom-readd-after-ack",
                    sura = 8,
                    ayah = 11,
                    bookmarkId = "remote-bookmark-readd-after-ack",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val bookmark = bookmarksRepository.getAllBookmarks().single()
        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(deleteMutation)
        )
        val clearedDelete = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(deleteMutation.localID.toLong())
            .executeAsOne()
        assertEquals(0L, clearedDelete.is_active)
        assertNull(clearedDelete.pending_op)
        assertEquals("remote-custom-readd-after-ack", clearedDelete.last_synced_collection_remote_id)
        assertEquals("remote-bookmark-readd-after-ack", clearedDelete.last_synced_bookmark_remote_id)

        collectionBookmarksRepository.addBookmarkToCollection(collectionId, bookmark)

        val readded = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(deleteMutation.localID.toLong())
            .executeAsOne()
        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(1L, readded.is_active)
        assertEquals("CREATED", readded.pending_op)
        assertEquals(Mutation.CREATED, createMutation.mutation)
        assertEquals("remote-custom-readd-after-ack", createMutation.model.collectionRemoteId)
        assertEquals("remote-bookmark-readd-after-ack", createMutation.model.bookmarkRemoteId)
    }

    @Test
    fun `deleteExisting import keeps pending reading create tombstone until bookmark ACK binds`() = runTest {
        val bookmark = readingRepository.addAyahReadingBookmark(8, 12, at(100))
        val readingCreate = bookmarksRepository.fetchMutatedBookmarks().single()

        PersistenceImportRepositoryImpl(database).importData(PersistenceImportData(), deleteExisting = true)

        val tombstoneBeforeAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals(1L, tombstoneBeforeAck.deleted)
        assertEquals(0L, tombstoneBeforeAck.is_reading)
        assertEquals("DELETED", tombstoneBeforeAck.bookmark_pending_op)
        assertNull(tombstoneBeforeAck.remote_id)
        assertTrue(bookmarksRepository.fetchMutatedBookmarks().none())

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(8, 12, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-import-reading-create",
                    mutation = Mutation.CREATED,
                    ack = readingCreate.ack
                )
            ),
            localMutationsToClear = listOf(readingCreate)
        )

        val tombstoneAfterAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals("remote-import-reading-create", tombstoneAfterAck.remote_id)
        assertEquals(1L, tombstoneAfterAck.deleted)
        assertEquals("DELETED", tombstoneAfterAck.bookmark_pending_op)
        val deleteMutation = bookmarksRepository.fetchMutatedBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-import-reading-create", deleteMutation.remoteID)
    }

    @Test
    fun `deleteExisting import keeps pending default create tombstone until default ACK binds`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(8, 13, listOf(DEFAULT_COLLECTION_ID), at(100))
        val defaultCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()

        PersistenceImportRepositoryImpl(database).importData(PersistenceImportData(), deleteExisting = true)

        val tombstoneBeforeAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals(1L, tombstoneBeforeAck.deleted)
        assertEquals(0L, tombstoneBeforeAck.is_in_default_collection)
        assertEquals("DELETED", tombstoneBeforeAck.default_pending_op)
        assertNull(tombstoneBeforeAck.remote_id)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())

        val defaultCreateAck = LocalModelMutation(
            model = defaultCreate.model.copy(bookmarkRemoteId = "remote-import-default-create"),
            remoteID = "$DEFAULT_COLLECTION_ID-remote-import-default-create",
            localID = defaultCreate.localID,
            mutation = defaultCreate.mutation,
            ack = defaultCreate.ack
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(defaultCreateAck)
        )

        val tombstoneAfterAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals("remote-import-default-create", tombstoneAfterAck.remote_id)
        assertEquals("DELETED", tombstoneAfterAck.default_pending_op)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-import-default-create", deleteMutation.model.bookmarkRemoteId)
    }

    @Test
    fun `deleteExisting import keeps pending custom link tombstone until link ACK binds`() = runTest {
        val collectionId = createCollection("ImportPendingCustom", "remote-import-pending-custom")
        bookmarksRepository.addBookmark(8, 14, listOf(collectionId), at(100))
        val customCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()

        PersistenceImportRepositoryImpl(database).importData(PersistenceImportData(), deleteExisting = true)

        val linkBeforeAck = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(customCreate.localID.toLong())
            .executeAsOne()
        assertEquals(0L, linkBeforeAck.is_active)
        assertEquals("DELETED", linkBeforeAck.pending_op)
        assertNull(linkBeforeAck.last_synced_bookmark_remote_id)
        assertNull(linkBeforeAck.last_synced_collection_remote_id)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())

        val customCreateAck = LocalModelMutation(
            model = customCreate.model.copy(bookmarkRemoteId = "remote-import-custom-bookmark"),
            remoteID = "remote-import-pending-custom-remote-import-custom-bookmark",
            localID = customCreate.localID,
            mutation = customCreate.mutation,
            ack = customCreate.ack
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(customCreateAck)
        )

        val linkAfterAck = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(customCreate.localID.toLong())
            .executeAsOne()
        val bookmarkAfterAck = database.bookmarksQueries.getBookmarkForAyah(8L, 14L).executeAsOne()
        assertEquals("remote-import-custom-bookmark", bookmarkAfterAck.remote_id)
        assertEquals("DELETED", linkAfterAck.pending_op)
        assertEquals("remote-import-custom-bookmark", linkAfterAck.last_synced_bookmark_remote_id)
        assertEquals("remote-import-pending-custom", linkAfterAck.last_synced_collection_remote_id)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-import-custom-bookmark", deleteMutation.model.bookmarkRemoteId)
        assertEquals("remote-import-pending-custom", deleteMutation.model.collectionRemoteId)
    }

    @Test
    fun `deleting collection preserves in-flight custom link create until create ACK binds delete`() = runTest {
        val collectionId = createCollection("PendingCustomDeleteCollection", "remote-pending-custom-delete-collection")
        bookmarksRepository.addBookmark(8, 15, listOf(collectionId), at(100))
        val customCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(customCreate.ack)))

        collectionsRepository.deleteCollection(collectionId)

        val linkBeforeAck = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(customCreate.localID.toLong())
            .executeAsOne()
        assertEquals(0L, linkBeforeAck.is_active)
        assertEquals("DELETED", linkBeforeAck.pending_op)
        assertNull(linkBeforeAck.last_synced_bookmark_remote_id)
        assertNull(linkBeforeAck.last_synced_collection_remote_id)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())

        val customCreateAck = LocalModelMutation(
            model = customCreate.model.copy(bookmarkRemoteId = "remote-pending-custom-delete-collection-row"),
            remoteID = "remote-pending-custom-delete-collection-remote-pending-custom-delete-collection-row",
            localID = customCreate.localID,
            mutation = customCreate.mutation,
            ack = customCreate.ack
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(customCreateAck)
        )

        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-pending-custom-delete-collection-row", deleteMutation.model.bookmarkRemoteId)
        assertEquals("remote-pending-custom-delete-collection", deleteMutation.model.collectionRemoteId)
    }

    @Test
    fun `deleting collection removes never-pushed custom link create`() = runTest {
        val collectionId = createCollection("NeverPushedCustomDeleteCollection", "remote-never-pushed-delete-collection")
        val bookmark = bookmarksRepository.addBookmark(8, 16, listOf(collectionId), at(100))
        val bookmarkRow = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        val linkBeforeDelete = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(bookmarkRow.local_id, collectionId.toLong())
            .executeAsOne()
        assertEquals("CREATED", linkBeforeDelete.pending_op)
        assertEquals(1L, linkBeforeDelete.pending_version)

        collectionsRepository.deleteCollection(collectionId)

        assertNull(
            database.bookmark_collectionsQueries
                .getCollectionBookmarkByLocalId(linkBeforeDelete.local_id)
                .executeAsOneOrNull()
        )
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `removing pending custom link create keeps tombstone until create ACK binds delete`() = runTest {
        val collectionId = createCollection("PendingCustomRemove", "remote-pending-custom-remove")
        val bookmark = bookmarksRepository.addBookmark(9, 1, listOf(collectionId), at(100))
        val customCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(customCreate.ack)))

        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)

        val linkBeforeAck = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(customCreate.localID.toLong())
            .executeAsOne()
        assertEquals(0L, linkBeforeAck.is_active)
        assertEquals("DELETED", linkBeforeAck.pending_op)
        assertNull(linkBeforeAck.last_synced_bookmark_remote_id)
        assertNull(linkBeforeAck.last_synced_collection_remote_id)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())

        val customCreateAck = LocalModelMutation(
            model = customCreate.model.copy(bookmarkRemoteId = "remote-pending-custom-bookmark"),
            remoteID = "remote-pending-custom-remove-remote-pending-custom-bookmark",
            localID = customCreate.localID,
            mutation = customCreate.mutation,
            ack = customCreate.ack
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(customCreateAck)
        )

        val linkAfterAck = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(customCreate.localID.toLong())
            .executeAsOne()
        assertEquals("DELETED", linkAfterAck.pending_op)
        assertEquals("remote-pending-custom-bookmark", linkAfterAck.last_synced_bookmark_remote_id)
        assertEquals("remote-pending-custom-remove", linkAfterAck.last_synced_collection_remote_id)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-pending-custom-bookmark", deleteMutation.model.bookmarkRemoteId)
        assertEquals("remote-pending-custom-remove", deleteMutation.model.collectionRemoteId)
    }

    @Test
    fun `deleting bookmark preserves pending custom link create until create ACK binds delete`() = runTest {
        val collectionId = createCollection("PendingCustomDeleteBookmark", "remote-pending-custom-delete-bookmark")
        val bookmark = bookmarksRepository.addBookmark(9, 2, listOf(collectionId), at(100))
        val customCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(customCreate.ack)))

        bookmarksRepository.deleteBookmark(bookmark)

        val linkBeforeAck = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(customCreate.localID.toLong())
            .executeAsOne()
        assertEquals(0L, linkBeforeAck.is_active)
        assertEquals("DELETED", linkBeforeAck.pending_op)
        assertNull(linkBeforeAck.last_synced_bookmark_remote_id)
        assertNull(linkBeforeAck.last_synced_collection_remote_id)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())

        val customCreateAck = LocalModelMutation(
            model = customCreate.model.copy(bookmarkRemoteId = "remote-pending-custom-delete-bookmark-row"),
            remoteID = "remote-pending-custom-delete-bookmark-remote-pending-custom-delete-bookmark-row",
            localID = customCreate.localID,
            mutation = customCreate.mutation,
            ack = customCreate.ack
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(customCreateAck)
        )

        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-pending-custom-delete-bookmark-row", deleteMutation.model.bookmarkRemoteId)
        assertEquals("remote-pending-custom-delete-bookmark", deleteMutation.model.collectionRemoteId)
    }

    @Test
    fun `failed planning before default create remove leaves no unpushable tombstone`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(9, 17, listOf(DEFAULT_COLLECTION_ID), at(100))
        val defaultCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, defaultCreate.mutation)

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)

        assertNull(database.bookmarksQueries.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOneOrNull())
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `failed planning before custom link create remove leaves no unpushable tombstone`() = runTest {
        val collectionId = createCollection("FailedCustomRemove", "remote-failed-custom-remove")
        val bookmark = bookmarksRepository.addBookmark(9, 18, listOf(collectionId), at(100))
        val linkCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, linkCreate.mutation)

        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)

        assertNull(
            database.bookmark_collectionsQueries
                .getCollectionBookmarkByLocalId(linkCreate.localID.toLong())
                .executeAsOneOrNull()
        )
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `failed push before default create remove leaves no unpushable tombstone`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(9, 21, listOf(DEFAULT_COLLECTION_ID), at(100))
        val defaultCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, defaultCreate.mutation)

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)

        assertNull(database.bookmarksQueries.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOneOrNull())
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `failed push rollback restores default create to never-pushed remove behavior`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(9, 25, listOf(DEFAULT_COLLECTION_ID), at(100))
        val defaultCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        val ack = assertNotNull(defaultCreate.ack)

        val marked = collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(ack))
        assertEquals(listOf(ack), marked)
        collectionBookmarksRepository.rollbackMutatedCollectionBookmarksInFlight(marked)

        val rowAfterRollback = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals("CREATED", rowAfterRollback.default_pending_op)
        assertEquals(1L, rowAfterRollback.default_pending_version)

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)

        assertNull(database.bookmarksQueries.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOneOrNull())
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `failed push rollback cleans removed in-flight default create tombstone`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(9, 26, listOf(DEFAULT_COLLECTION_ID), at(100))
        val defaultCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        val marked = collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(
            listOf(assertNotNull(defaultCreate.ack))
        )

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)
        collectionBookmarksRepository.rollbackMutatedCollectionBookmarksInFlight(marked)

        assertNull(database.bookmarksQueries.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOneOrNull())
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `failed push before custom link create remove leaves no unpushable tombstone`() = runTest {
        val collectionId = createCollection("FailedPushCustomRemove", "remote-failed-push-custom-remove")
        val bookmark = bookmarksRepository.addBookmark(9, 22, listOf(collectionId), at(100))
        val linkCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, linkCreate.mutation)

        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)

        assertNull(
            database.bookmark_collectionsQueries
                .getCollectionBookmarkByLocalId(linkCreate.localID.toLong())
                .executeAsOneOrNull()
        )
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `failed push rollback restores custom link create to never-pushed remove behavior`() = runTest {
        val collectionId = createCollection("FailedPushCustomRollback", "remote-failed-push-custom-rollback")
        val bookmark = bookmarksRepository.addBookmark(9, 27, listOf(collectionId), at(100))
        val linkCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        val ack = assertNotNull(linkCreate.ack)

        val marked = collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(ack))
        assertEquals(listOf(ack), marked)
        collectionBookmarksRepository.rollbackMutatedCollectionBookmarksInFlight(marked)

        val linkAfterRollback = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(linkCreate.localID.toLong())
            .executeAsOne()
        assertEquals("CREATED", linkAfterRollback.pending_op)
        assertEquals(1L, linkAfterRollback.pending_version)

        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)

        assertNull(
            database.bookmark_collectionsQueries
                .getCollectionBookmarkByLocalId(linkCreate.localID.toLong())
                .executeAsOneOrNull()
        )
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `failed push rollback cleans removed in-flight custom link create tombstone`() = runTest {
        val collectionId = createCollection("FailedPushCustomRemovedRollback", "remote-failed-custom-removed-rollback")
        val bookmark = bookmarksRepository.addBookmark(9, 28, listOf(collectionId), at(100))
        val linkCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        val marked = collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(
            listOf(assertNotNull(linkCreate.ack))
        )

        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        collectionBookmarksRepository.rollbackMutatedCollectionBookmarksInFlight(marked)

        assertNull(
            database.bookmark_collectionsQueries
                .getCollectionBookmarkByLocalId(linkCreate.localID.toLong())
                .executeAsOneOrNull()
        )
        assertNull(database.bookmarksQueries.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOneOrNull())
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `direct default add starts at one pending version and removes before push cleanly`() = runTest {
        val defaultBookmark = collectionBookmarksRepository.addAyahBookmarkToCollection(
            DEFAULT_COLLECTION_ID,
            9,
            23,
            at(100)
        )
        val row = database.bookmarksQueries
            .getBookmarkByLocalId(defaultBookmark.bookmarkLocalId.toLong())
            .executeAsOne()
        assertEquals("CREATED", row.default_pending_op)
        assertEquals(1L, row.default_pending_version)

        collectionBookmarksRepository.removeAyahBookmarkFromCollection(defaultBookmark)

        assertNull(
            database.bookmarksQueries
                .getBookmarkByLocalId(defaultBookmark.bookmarkLocalId.toLong())
                .executeAsOneOrNull()
        )
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `re-adding default create deleted before ACK lets create ACK bind remote id`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(9, 19, listOf(DEFAULT_COLLECTION_ID), at(100))
        val defaultCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(defaultCreate.ack)))

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)
        bookmarksRepository.addBookmark(9, 19, listOf(DEFAULT_COLLECTION_ID), at(150))

        val beforeAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals(1L, beforeAck.is_in_default_collection)
        assertEquals("DELETED", beforeAck.default_pending_op)
        assertNull(beforeAck.remote_id)

        val createAck = LocalModelMutation(
            model = defaultCreate.model.copy(bookmarkRemoteId = "remote-default-readd"),
            remoteID = "$DEFAULT_COLLECTION_ID-remote-default-readd",
            localID = defaultCreate.localID,
            mutation = defaultCreate.mutation,
            ack = defaultCreate.ack
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(createAck)
        )

        val afterAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals(1L, afterAck.is_in_default_collection)
        assertEquals("remote-default-readd", afterAck.remote_id)
        assertNull(afterAck.default_pending_op)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `remove readd remove default create before ACK keeps tombstone until create ACK binds delete`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(9, 24, listOf(DEFAULT_COLLECTION_ID), at(100))
        val defaultCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(defaultCreate.ack)))

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)
        bookmarksRepository.addBookmark(9, 24, listOf(DEFAULT_COLLECTION_ID), at(150))
        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)

        val beforeAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals(0L, beforeAck.is_in_default_collection)
        assertEquals("DELETED", beforeAck.default_pending_op)
        assertNull(beforeAck.remote_id)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())

        val createAck = LocalModelMutation(
            model = defaultCreate.model.copy(bookmarkRemoteId = "remote-default-readd-remove"),
            remoteID = "$DEFAULT_COLLECTION_ID-remote-default-readd-remove",
            localID = defaultCreate.localID,
            mutation = defaultCreate.mutation,
            ack = defaultCreate.ack
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(createAck)
        )

        val afterAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals("remote-default-readd-remove", afterAck.remote_id)
        assertEquals("DELETED", afterAck.default_pending_op)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-default-readd-remove", deleteMutation.model.bookmarkRemoteId)
    }

    @Test
    fun `composite-only default create ACK preserves relation snapshot without parent id backfill`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(9, 30, listOf(DEFAULT_COLLECTION_ID), at(100))
        val defaultCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        val createAck = assertNotNull(defaultCreate.ack)
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(createAck))
        val markedAck = createAck.copy(observedPendingVersion = createAck.observedPendingVersion + 1)

        val pushedCreate = LocalModelMutation(
            model = defaultCreate.model,
            remoteID = "$DEFAULT_COLLECTION_ID-remote-composite-default-bookmark",
            localID = defaultCreate.localID,
            mutation = defaultCreate.mutation,
            ack = markedAck
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(pushedCreate)
        )

        val afterAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertNull(afterAck.remote_id)
        assertNull(afterAck.default_pending_op)
        assertNotNull(
            collectionBookmarksRepository.fetchCollectionBookmarkByRemoteId(
                "$DEFAULT_COLLECTION_ID-remote-composite-default-bookmark"
            )
        )
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)

        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("$DEFAULT_COLLECTION_ID-remote-composite-default-bookmark", deleteMutation.remoteID)
        assertEquals("remote-composite-default-bookmark", deleteMutation.model.bookmarkRemoteId)
        assertNull(
            database.bookmarksQueries
                .getBookmarkByLocalId(bookmark.localId.toLong())
                .executeAsOne()
                .remote_id
        )
    }

    @Test
    fun `removing pending default create keeps delete state for remote id backfill`() = runTest {
        val localOnlyBookmark = bookmarksRepository.addBookmark(9, 3, listOf(DEFAULT_COLLECTION_ID), at(100))
        val localOnlyCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(localOnlyCreate.ack)))

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, localOnlyBookmark)

        val localOnlyBeforeAck = database.bookmarksQueries
            .getBookmarkByLocalId(localOnlyBookmark.localId.toLong())
            .executeAsOne()
        assertEquals(0L, localOnlyBeforeAck.is_in_default_collection)
        assertEquals("DELETED", localOnlyBeforeAck.default_pending_op)
        assertNull(localOnlyBeforeAck.remote_id)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())

        val localOnlyCreateAck = LocalModelMutation(
            model = localOnlyCreate.model.copy(bookmarkRemoteId = "remote-pending-default-local"),
            remoteID = "$DEFAULT_COLLECTION_ID-remote-pending-default-local",
            localID = localOnlyCreate.localID,
            mutation = localOnlyCreate.mutation,
            ack = localOnlyCreate.ack
        )
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(localOnlyCreateAck)
        )

        val localOnlyDelete = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, localOnlyDelete.mutation)
        assertEquals("remote-pending-default-local", localOnlyDelete.model.bookmarkRemoteId)

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(9, 4, isReading = false, lastUpdated = at(100)),
                    remoteID = "remote-pending-default-backed",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val remoteBackedBookmark = bookmarksRepository.addBookmark(9, 4, listOf(DEFAULT_COLLECTION_ID), at(150))
        val remoteBackedCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single {
            it.localID == "default:${remoteBackedBookmark.localId}"
        }
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(remoteBackedCreate.ack)))

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, remoteBackedBookmark)
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(remoteBackedCreate)
        )

        val remoteBackedRow = database.bookmarksQueries
            .getBookmarkByLocalId(remoteBackedBookmark.localId.toLong())
            .executeAsOne()
        assertEquals("remote-pending-default-backed", remoteBackedRow.remote_id)
        assertEquals("DELETED", remoteBackedRow.default_pending_op)
        val remoteBackedDelete = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single {
            it.localID == "default:${remoteBackedBookmark.localId}"
        }
        assertEquals(Mutation.DELETED, remoteBackedDelete.mutation)
        assertEquals("remote-pending-default-backed", remoteBackedDelete.model.bookmarkRemoteId)
    }

    @Test
    fun `deleting in-flight reading-only create keeps bookmark tombstone until create ACK binds delete`() = runTest {
        val bookmark = readingRepository.addAyahReadingBookmark(9, 5, at(100))
        val readingCreate = bookmarksRepository.fetchMutatedBookmarks().single()
        bookmarksRepository.markMutatedBookmarksInFlight(listOf(assertNotNull(readingCreate.ack)))

        assertTrue(readingRepository.deleteReadingBookmark())

        val tombstoneBeforeAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals(1L, tombstoneBeforeAck.deleted)
        assertEquals(0L, tombstoneBeforeAck.is_reading)
        assertEquals("DELETED", tombstoneBeforeAck.bookmark_pending_op)
        assertNull(tombstoneBeforeAck.reading_pending_op)
        assertNull(tombstoneBeforeAck.remote_id)
        assertTrue(bookmarksRepository.fetchMutatedBookmarks().none())

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(9, 5, isReading = true, lastUpdated = at(100)),
                    remoteID = "remote-pending-reading-create",
                    mutation = Mutation.CREATED,
                    ack = readingCreate.ack
                )
            ),
            localMutationsToClear = listOf(readingCreate)
        )

        val tombstoneAfterAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals("remote-pending-reading-create", tombstoneAfterAck.remote_id)
        assertEquals(1L, tombstoneAfterAck.deleted)
        assertEquals("DELETED", tombstoneAfterAck.bookmark_pending_op)
        val deleteMutation = bookmarksRepository.fetchMutatedBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-pending-reading-create", deleteMutation.remoteID)
    }

    @Test
    fun `cleared saved reading create remains postable and clears incremented ACK version`() = runTest {
        val bookmark = readingRepository.addAyahReadingBookmark(9, 21, at(100))
        bookmarksRepository.addBookmark(9, 21, at(125))

        assertTrue(readingRepository.deleteReadingBookmark())

        val readingClear = bookmarksRepository.fetchMutatedBookmarks().single {
            it.localID == bookmark.localId
        }
        val ack = assertNotNull(readingClear.ack)
        assertEquals(Mutation.CREATED, readingClear.mutation)
        assertEquals(false, readingClear.model.isReading)

        val marked = bookmarksRepository.markMutatedBookmarksInFlight(listOf(ack))

        assertEquals(listOf(ack), marked)
        val markedAck = ack.copy(observedPendingVersion = ack.observedPendingVersion + 1)
        val markedMutation = LocalModelMutation(
            model = readingClear.model,
            remoteID = readingClear.remoteID,
            localID = readingClear.localID,
            mutation = readingClear.mutation,
            ack = markedAck
        )
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = readingClear.model,
                    remoteID = "remote-cleared-saved-reading",
                    mutation = Mutation.CREATED,
                    ack = markedAck
                )
            ),
            localMutationsToClear = listOf(markedMutation)
        )

        val row = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals("remote-cleared-saved-reading", row.remote_id)
        assertEquals(0L, row.is_reading)
        assertEquals(1L, row.is_in_default_collection)
        assertNull(row.reading_pending_op)
        assertEquals(markedAck.observedPendingVersion, row.reading_pending_version)
        assertTrue(bookmarksRepository.fetchMutatedBookmarks().none {
            it.localID == bookmark.localId
        })
    }

    @Test
    fun `clearing saved reading after marker leaves newer final state pending`() = runTest {
        val bookmark = readingRepository.addAyahReadingBookmark(9, 22, at(100))
        bookmarksRepository.addBookmark(9, 22, at(125))
        val readingCreate = bookmarksRepository.fetchMutatedBookmarks().single {
            it.localID == bookmark.localId
        }
        val ack = assertNotNull(readingCreate.ack)

        val marked = bookmarksRepository.markMutatedBookmarksInFlight(listOf(ack))
        assertEquals(listOf(ack), marked)
        assertTrue(readingRepository.deleteReadingBookmark())

        val markedAck = ack.copy(observedPendingVersion = ack.observedPendingVersion + 1)
        val markedMutation = LocalModelMutation(
            model = readingCreate.model,
            remoteID = readingCreate.remoteID,
            localID = readingCreate.localID,
            mutation = readingCreate.mutation,
            ack = markedAck
        )
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = readingCreate.model,
                    remoteID = "remote-inflight-saved-reading",
                    mutation = Mutation.CREATED,
                    ack = markedAck
                )
            ),
            localMutationsToClear = listOf(markedMutation)
        )

        val row = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        assertEquals("remote-inflight-saved-reading", row.remote_id)
        assertEquals(0L, row.is_reading)
        assertEquals("CREATED", row.reading_pending_op)
        assertEquals(markedAck.observedPendingVersion + 1, row.reading_pending_version)

        val finalReadingState = bookmarksRepository.fetchMutatedBookmarks().single {
            it.localID == bookmark.localId
        }
        assertEquals(Mutation.CREATED, finalReadingState.mutation)
        assertEquals(false, finalReadingState.model.isReading)
        assertEquals(row.reading_pending_version, assertNotNull(finalReadingState.ack).observedPendingVersion)
    }

    @Test
    fun `failed push rollback cleans removed in-flight reading-only create tombstone`() = runTest {
        val bookmark = readingRepository.addAyahReadingBookmark(9, 29, at(100))
        val readingCreate = bookmarksRepository.fetchMutatedBookmarks().single()
        val marked = bookmarksRepository.markMutatedBookmarksInFlight(listOf(assertNotNull(readingCreate.ack)))

        assertTrue(readingRepository.deleteReadingBookmark())
        bookmarksRepository.rollbackMutatedBookmarksInFlight(marked)

        assertNull(database.bookmarksQueries.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOneOrNull())
        assertTrue(bookmarksRepository.fetchMutatedBookmarks().none())
    }

    @Test
    fun `deleting never-pushed reading-only create removes local row`() = runTest {
        val bookmark = readingRepository.addAyahReadingBookmark(9, 20, at(100))
        val readingCreate = bookmarksRepository.fetchMutatedBookmarks().single()
        assertEquals(Mutation.CREATED, readingCreate.mutation)

        assertTrue(readingRepository.deleteReadingBookmark())

        assertNull(database.bookmarksQueries.getBookmarkByLocalId(bookmark.localId.toLong()).executeAsOneOrNull())
        assertTrue(bookmarksRepository.fetchMutatedBookmarks().none())
    }

    @Test
    fun `custom link snapshots retire after bookmark and link deletes are ACKed`() = runTest {
        val collectionId = createCollection("RetireCustomSnapshot", "remote-retire-custom-snapshot")
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-retire-custom-snapshot",
                    sura = 9,
                    ayah = 6,
                    bookmarkId = "remote-retire-custom-bookmark",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val bookmark = bookmarksRepository.getAllBookmarks().single()
        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        val linkDelete = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(linkDelete)
        )

        val ackClearedLink = assertNotNull(
            database.bookmark_collectionsQueries
                .getCollectionBookmarkByLocalId(linkDelete.localID.toLong())
                .executeAsOneOrNull()
        )
        assertNull(ackClearedLink.pending_op)

        val bookmarkRow = database.bookmarksQueries
            .getBookmarkByRemoteId("remote-retire-custom-bookmark")
            .executeAsOne()
        database.bookmarksQueries.markBookmarkDeleted(local_id = bookmarkRow.local_id, timestamp = 200L)
        val bookmarkDelete = bookmarksRepository.fetchMutatedBookmarks().single()

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(bookmarkDelete)
        )

        assertNull(database.bookmarksQueries.getBookmarkByLocalId(bookmarkRow.local_id).executeAsOneOrNull())
        assertNull(
            database.bookmark_collectionsQueries
                .getCollectionBookmarkByLocalId(linkDelete.localID.toLong())
                .executeAsOneOrNull()
        )
    }

    @Test
    fun `remote bookmark create replay after local reading delete backfills id and leaves delete pending`() = runTest {
        val bookmark = readingRepository.addAyahReadingBookmark(10, 1, at(100))
        val readingCreate = bookmarksRepository.fetchMutatedBookmarks().single()
        bookmarksRepository.markMutatedBookmarksInFlight(listOf(assertNotNull(readingCreate.ack)))

        assertTrue(readingRepository.deleteReadingBookmark())
        assertTrue(bookmarksRepository.fetchMutatedBookmarks().none())

        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(10, 1, isReading = true, lastUpdated = at(200)),
                    remoteID = "remote-replayed-reading-bookmark",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        val deleteMutation = bookmarksRepository.fetchMutatedBookmarks().single()
        assertEquals("remote-replayed-reading-bookmark", row.remote_id)
        assertEquals(1L, row.deleted)
        assertEquals(0L, row.is_reading)
        assertEquals("DELETED", row.bookmark_pending_op)
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-replayed-reading-bookmark", deleteMutation.remoteID)
    }

    @Test
    fun `remote default link create replay after local remove keeps default delete pending`() = runTest {
        val bookmark = bookmarksRepository.addBookmark(10, 2, listOf(DEFAULT_COLLECTION_ID), at(100))
        val defaultCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(defaultCreate.ack)))

        collectionBookmarksRepository.removeBookmarkFromCollection(DEFAULT_COLLECTION_ID, bookmark)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                defaultRemoteMutation(
                    sura = 10,
                    ayah = 2,
                    bookmarkId = "remote-replayed-default-bookmark",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals("remote-replayed-default-bookmark", row.remote_id)
        assertEquals(0L, row.is_in_default_collection)
        assertEquals("DELETED", row.default_pending_op)
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-replayed-default-bookmark", deleteMutation.model.bookmarkRemoteId)
    }

    @Test
    fun `remote custom link create replay after local remove keeps link delete pending`() = runTest {
        val collectionId = createCollection("ReplayRemovedCustom", "remote-replayed-custom-collection")
        val bookmark = bookmarksRepository.addBookmark(10, 3, listOf(collectionId), at(100))
        val customCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(customCreate.ack)))

        collectionBookmarksRepository.removeBookmarkFromCollection(collectionId, bookmark)
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-replayed-custom-collection",
                    sura = 10,
                    ayah = 3,
                    bookmarkId = "remote-replayed-custom-bookmark",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.localId.toLong())
            .executeAsOne()
        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(customCreate.localID.toLong())
            .executeAsOne()
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals("remote-replayed-custom-bookmark", row.remote_id)
        assertEquals(0L, link.is_active)
        assertEquals("DELETED", link.pending_op)
        assertEquals("remote-replayed-custom-bookmark", link.last_synced_bookmark_remote_id)
        assertEquals("remote-replayed-custom-collection", link.last_synced_collection_remote_id)
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-replayed-custom-bookmark", deleteMutation.model.bookmarkRemoteId)
        assertEquals("remote-replayed-custom-collection", deleteMutation.model.collectionRemoteId)
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

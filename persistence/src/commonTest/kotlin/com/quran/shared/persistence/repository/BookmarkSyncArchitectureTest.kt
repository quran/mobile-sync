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
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.CollectionAyahBookmark
import com.quran.shared.persistence.repository.bookmark.BookmarkDependencyReconciler
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.toPlatform
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
        database.collectionsQueries.deleteAll()
        bookmarksRepository = BookmarksRepositoryImpl(database)
        readingRepository = ReadingBookmarksRepositoryImpl(database)
        collectionsRepository = CollectionsRepositoryImpl(database)
        collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(database)
    }

    @Test
    fun `offline bookmark joins seeded default collection without collection mutation`() = runTest {
        val seededDatabase = QuranDatabase(TestDatabaseDriver().createDriver())
        val seededCollections = CollectionsRepositoryImpl(seededDatabase)
        val seededCollectionBookmarks = CollectionBookmarksRepositoryImpl(seededDatabase)

        val defaultCollection = seededDatabase.collectionsQueries.getDefaultCollection().executeAsOne()
        seededCollectionBookmarks.addAyahBookmarkToCollection(
            defaultCollection.local_id.toString(),
            2,
            255,
            at(100)
        )
        val bookmarks = seededCollectionBookmarks
            .getBookmarksForCollection(defaultCollection.local_id.toString())
        assertEquals(listOf(2 to 255), bookmarks.map { it.sura to it.ayah })
        assertEquals(emptyList(), seededCollections.fetchMutatedCollections())
    }

    @Test
    fun `remote default collection membership uses the normal relation table`() = runTest {
        persistDefaultCollection()

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "__default__",
                    sura = 2,
                    ayah = 255,
                    bookmarkId = "remote-default-bookmark",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val defaultCollection = database.collectionsQueries.getDefaultCollection().executeAsOne()
        val memberships = collectionBookmarksRepository
            .getBookmarksForCollection(defaultCollection.local_id.toString())
        assertEquals(listOf(2 to 255), memberships.map { it.sura to it.ayah })
        assertEquals(
            1L,
            database.bookmark_collectionsQueries
                .countActiveForBookmark(memberships.single().bookmarkId.toLong())
                .executeAsOne()
        )
    }

    @Test
    fun `fetchMutatedCollectionBookmarks carries custom relation created_at`() = runTest {
        persistDefaultCollection()
        val bookmark = seedBookmark(2, 255, at(100))
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-custom-created-at-collection",
            name = "Favorites",
            created_at = 500,
            modified_at = 500,
            is_default = 0L,
            is_system = 0L
        )
        val collection = database.collectionsQueries
            .getCollectionByRemoteId("remote-custom-created-at-collection")
            .executeAsOne()

        addBookmarkToCollection(collection.local_id.toString(), bookmark, at(2000))
        addBookmarkToCollection(collection.local_id.toString(), bookmark, at(3000))

        val mutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single {
            it.model.collectionLocalId == collection.local_id.toString()
        }

        assertEquals(2000L, mutation.model.createdAt?.fromPlatform()?.toEpochMilliseconds())
        assertEquals(3000L, mutation.model.lastUpdated.fromPlatform().toEpochMilliseconds())
    }


    @Test
    fun `remote created ayah bookmark persists created_at separately from modified_at`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(
                        sura = 2,
                        ayah = 255,
                        isReading = false,
                        lastUpdated = at(2345),
                        createdAt = at(1000)
                    ),
                    remoteID = "remote-ayah-created-at",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-ayah-created-at").executeAsOne()
        assertEquals(1000L, row.created_at)
        assertEquals(2345L, row.modified_at)
        assertEquals(2345L, row.bookmark_modified_at)
    }

    @Test
    fun `remote created page bookmark persists created_at separately from modified_at`() = runTest {
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Page(
                        page = 22,
                        isReading = false,
                        lastUpdated = at(2345),
                        createdAt = at(1000)
                    ),
                    remoteID = "remote-page-created-at",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-page-created-at").executeAsOne()
        assertEquals(1000L, row.created_at)
        assertEquals(2345L, row.modified_at)
        assertEquals(2345L, row.bookmark_modified_at)
    }

    @Test
    fun `remote created custom collection bookmark persists created_at separately from modified_at`() = runTest {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "remote-custom-created-at",
            name = "Favorites",
            created_at = 500,
            modified_at = 500,
            is_default = 0L,
            is_system = 0L
        )
        bookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(
                        sura = 2,
                        ayah = 255,
                        isReading = false,
                        lastUpdated = at(1500),
                        createdAt = at(1500)
                    ),
                    remoteID = "remote-bookmark-created-at",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollectionBookmark.Ayah(
                        collectionId = "remote-custom-created-at",
                        sura = 2,
                        ayah = 255,
                        lastUpdated = at(2345),
                        bookmarkId = "remote-bookmark-created-at",
                        createdAt = at(1000)
                    ),
                    remoteID = "remote-custom-created-at-remote-bookmark-created-at",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val bookmark = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-created-at").executeAsOne()
        val collection = database.collectionsQueries.getCollectionByRemoteId("remote-custom-created-at").executeAsOne()
        val row = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(bookmark.local_id, collection.local_id)
            .executeAsOne()
        assertEquals(1000L, row.created_at)
        assertEquals(2345L, row.modified_at)
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
    fun `replaceAyahBookmarkCollections with timestamp applies timestamp when creating missing bookmark`() = runTest {
        val collectionId = createCollection("ReplaceAyahTimestamp", "remote-replace-ayah-timestamp")
        val timestamp = at(4300)

        val result = bookmarksRepository.replaceAyahBookmarkCollections(
            sura = 2,
            ayah = 14,
            collectionIds = listOf(collectionId),
            timestamp = timestamp
        )

        val bookmark = assertNotNull(result.bookmark)
        val row = database.bookmarksQueries.getBookmarkByLocalId(bookmark.id.toLong()).executeAsOne()
        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(bookmark.id.toLong(), collectionId.toLong())
            .executeAsOne()
        assertTrue(result.changed)
        assertEquals(4300L, row.created_at)
        assertEquals(4300L, row.modified_at)
        assertEquals(4300L, row.bookmark_modified_at)
        assertEquals(4300L, link.created_at)
        assertEquals(4300L, link.modified_at)
    }

    @Test
    fun `empty collection replacement removes an existing saved bookmark`() = runTest {
        val collectionId = createCollection("RemoveWithEmptyReplacement", "remote-remove-with-empty-replacement")
        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 15, at(100))

        val result = bookmarksRepository.replaceAyahBookmarkCollections(
            sura = 2,
            ayah = 15,
            collectionIds = emptyList(),
            timestamp = at(200)
        )

        assertTrue(result.changed)
        assertNull(result.bookmark)
        assertNull(database.bookmarksQueries.getBookmarkForAyah(2L, 15L).executeAsOneOrNull())
        assertEquals(0L, database.bookmark_collectionsQueries.countAll().executeAsOne())
    }

    @Test
    fun `empty collection replacement does nothing when bookmark is missing`() = runTest {
        val result = bookmarksRepository.replaceAyahBookmarkCollections(
            sura = 2,
            ayah = 16,
            collectionIds = emptyList(),
            timestamp = at(200)
        )

        assertFalse(result.changed)
        assertNull(result.bookmark)
        assertNull(database.bookmarksQueries.getBookmarkForAyah(2L, 16L).executeAsOneOrNull())
        assertEquals(0L, database.bookmark_collectionsQueries.countAll().executeAsOne())
    }

    @Test
    fun `empty collection replacement preserves reading bookmark facet`() = runTest {
        val collectionId = createCollection("PreserveReadingWithEmpty", "remote-preserve-reading-with-empty")
        readingRepository.addAyahReadingBookmark(2, 17, at(100))
        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 17, at(100))

        val result = bookmarksRepository.replaceAyahBookmarkCollections(
            sura = 2,
            ayah = 17,
            collectionIds = emptyList(),
            timestamp = at(200)
        )

        val row = database.bookmarksQueries.getBookmarkForAyah(2L, 17L).executeAsOne()
        assertTrue(result.changed)
        assertNull(result.bookmark)
        assertEquals(1L, row.is_reading)
        assertEquals(0L, database.bookmark_collectionsQueries.countActiveForBookmark(row.local_id).executeAsOne())
    }

    @Test
    fun `collection replacement preserves highlight membership`() = runTest {
        val firstCollectionId = createCollection("ReplacePreserveHighlightFirst", "remote-replace-highlight-first")
        val secondCollectionId = createCollection("ReplacePreserveHighlightSecond", "remote-replace-highlight-second")
        collectionBookmarksRepository.setHighlight(2, 18, AyahHighlightColor.GREEN, at(100))
        collectionBookmarksRepository.addAyahBookmarkToCollection(firstCollectionId, 2, 18, at(100))

        val result = bookmarksRepository.replaceAyahBookmarkCollections(
            sura = 2,
            ayah = 18,
            collectionIds = listOf(secondCollectionId),
            timestamp = at(200)
        )

        val bookmark = database.bookmarksQueries.getBookmarkForAyah(2L, 18L).executeAsOne()
        val highlightCollection = database.collectionsQueries
            .getCollectionByName(AyahHighlightColor.GREEN.collectionName)
            .executeAsOne()
        assertTrue(result.changed)
        assertNotNull(result.bookmark)
        assertEquals(
            setOf(highlightCollection.local_id, secondCollectionId.toLong()),
            database.bookmark_collectionsQueries
                .getActiveCollectionLocalIdsForBookmark(bookmark.local_id)
                .executeAsList()
                .toSet()
        )
        assertEquals(
            AyahHighlightColor.GREEN,
            collectionBookmarksRepository.getHighlightsFlow().first().single().color
        )
    }

    @Test
    fun `empty collection replacement preserves highlight membership`() = runTest {
        val collectionId = createCollection("RemovePreserveHighlight", "remote-remove-preserve-highlight")
        collectionBookmarksRepository.setHighlight(2, 19, AyahHighlightColor.PURPLE, at(100))
        collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, 2, 19, at(100))

        val result = bookmarksRepository.replaceAyahBookmarkCollections(
            sura = 2,
            ayah = 19,
            collectionIds = emptyList(),
            timestamp = at(200)
        )

        val bookmark = database.bookmarksQueries.getBookmarkForAyah(2L, 19L).executeAsOne()
        val highlightCollection = database.collectionsQueries
            .getCollectionByName(AyahHighlightColor.PURPLE.collectionName)
            .executeAsOne()
        assertTrue(result.changed)
        assertNull(result.bookmark)
        assertEquals(
            listOf(highlightCollection.local_id),
            database.bookmark_collectionsQueries
                .getActiveCollectionLocalIdsForBookmark(bookmark.local_id)
                .executeAsList()
        )
        assertEquals(
            AyahHighlightColor.PURPLE,
            collectionBookmarksRepository.getHighlightsFlow().first().single().color
        )
    }

    @Test
    fun `add page reading bookmark stores reading facet`() = runTest {
        val bookmark = readingRepository.addPageReadingBookmark(42)

        val row = database.bookmarksQueries.getBookmarkForPage(42L).executeAsOne()
        assertEquals(bookmark.page.toLong(), row.page)
        assertEquals(1L, row.is_reading)
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
    fun `custom collection create ack without proven parent id stores relation snapshot only`() = runTest {
        val collectionId = createCollection("UnprovenCustomParent", "remote-unproven-custom")
        val bookmark = seedBookmark(4, 22, listOf(collectionId))
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

        val bookmarkRow = database.bookmarksQueries.getBookmarkByLocalId(bookmark.id.toLong()).executeAsOne()
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
        val bookmark = seedBookmark(4, 23, listOf(collectionId))
        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(createMutation.ack)))
        removeBookmarkFromCollection(collectionId, bookmark)
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

        val bookmarkRow = database.bookmarksQueries.getBookmarkByLocalId(bookmark.id.toLong()).executeAsOne()
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
    fun `deleting collection keeps synced custom links pending deletion`() = runTest {
        val collectionId = createCollection("DeleteCollection", "remote-delete-collection")
        seedBookmark(4, 4, listOf(collectionId))
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
        val collectionRow = database.collectionsQueries.getCollectionByLocalId(collectionId.toLong()).executeAsOne()
        val bookmarkRow = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-4-4").executeAsOne()
        val linkRow = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(bookmarkRow.local_id, collectionId.toLong())
            .executeAsOne()
        assertEquals(Mutation.DELETED, deletion.mutation)
        assertEquals("remote-delete-collection", deletion.model.collectionRemoteId)
        assertEquals("remote-bookmark-4-4", deletion.model.bookmarkRemoteId)
        assertEquals(collectionRow.modified_at, linkRow.modified_at)
        assertEquals(collectionRow.modified_at, deletion.model.lastUpdated.fromPlatform().toEpochMilliseconds())
    }

    @Test
    fun `acknowledged custom link delete prunes otherwise orphaned bookmark`() = runTest {
        val collectionId = createCollection("DeleteOnlyCustom", "remote-delete-only-custom")
        seedBookmark(4, 5, listOf(collectionId))
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

        val bookmark = collectionBookmarksRepository.getBookmarksForCollection(collectionId).single()
        removeBookmarkFromCollection(collectionId, bookmark)
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
        seedBookmark(4, 14, listOf(collectionId))
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

        val bookmark = collectionBookmarksRepository.getBookmarksForCollection(collectionId).single()
        removeBookmarkFromCollection(collectionId, bookmark)
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
        val bookmark = seedBookmark(4, 10, listOf(collectionId))
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
            local_id = collectionId.toLong(),
            is_default = 0L,
            is_system = 0L
        )
        addBookmarkToCollection(collectionId, bookmark, at(300))

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
        assertEquals(300L, recreated.model.lastUpdated.fromPlatform().toEpochMilliseconds())
        val row = database.bookmarksQueries.getBookmarkForAyah(4L, 10L).executeAsOne()
        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(row.local_id, collectionId.toLong())
            .executeAsOne()
        assertEquals(300L, link.modified_at)
        assertEquals(1L, database.bookmark_collectionsQueries.countActiveForBookmark(row.local_id).executeAsOne())
    }

    @Test
    fun `changed custom link snapshots use reconciler timestamp`() = runTest {
        val collectionId = createCollection("SnapshotTimestamp", "remote-snapshot-old")
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-snapshot-old",
                    sura = 4,
                    ayah = 11,
                    bookmarkId = "remote-bookmark-snapshot",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = "remote-snapshot-new",
            name = "SnapshotTimestamp",
            modified_at = 200L,
            local_id = collectionId.toLong(),
            is_default = 0L,
            is_system = 0L
        )

        BookmarkDependencyReconciler(database).reconcile(777L)

        val recreated = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        val bookmark = database.bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-snapshot").executeAsOne()
        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(bookmark.local_id, collectionId.toLong())
            .executeAsOne()
        assertEquals(Mutation.CREATED, recreated.mutation)
        assertEquals("remote-snapshot-new", recreated.model.collectionRemoteId)
        assertEquals(777L, link.modified_at)
        assertEquals(777L, recreated.model.lastUpdated.fromPlatform().toEpochMilliseconds())
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
        val bookmark = collectionBookmarksRepository.getBookmarksForCollection(collectionId).single { it.sura == 4 && it.ayah == 14 }

        removeBookmarkFromCollection(collectionId, bookmark)
        assertEquals(Mutation.DELETED, collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single().mutation)

        addBookmarkToCollection(collectionId, bookmark)

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
        val bookmark = collectionBookmarksRepository.getBookmarksForCollection(collectionId).single { it.sura == 4 && it.ayah == 17 }

        removeBookmarkFromCollection(collectionId, bookmark)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        addBookmarkToCollection(collectionId, bookmark)

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
        seedBookmark(4, 15, listOf(collectionId))
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
        val bookmark = seedBookmark(4, 19, listOf(collectionId))
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
            local_id = collectionId.toLong(),
            is_default = 0L,
            is_system = 0L
        )
        addBookmarkToCollection(collectionId, bookmark)

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
        persistDefaultCollection()
        seedBookmark(5, 14, at(100))

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
            created_at = 100L,
            modified_at = 100L
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
        seedBookmark(4, 12, listOf(collectionId))
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
        seedBookmark(4, 9, listOf(collectionId))
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
        val bookmark = seedBookmark(5, 1, listOf(collectionId))
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
            local_id = collectionId.toLong(),
            is_default = 0L,
            is_system = 0L
        )
        addBookmarkToCollection(collectionId, bookmark)

        val recreated = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, recreated.mutation)
        assertEquals("remote-new-collection", recreated.model.collectionRemoteId)
        assertEquals("remote-bookmark-5-1", recreated.model.bookmarkRemoteId)
    }

    @Test
    fun `stale bookmark id in collection link does not recreate other active links`() = runTest {
        val firstCollectionId = createCollection("First", "remote-first-collection")
        val secondCollectionId = createCollection("Second", "remote-second-collection")
        seedBookmark(5, 2, listOf(firstCollectionId, secondCollectionId))
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
    fun `reading bookmark delete stamps derived inactive custom link delete with same timestamp`() = runTest {
        val collectionId = createCollection("ReadingDeleteLinkTimestamp", "remote-reading-delete-link-timestamp")
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                customRemoteMutation(
                    collectionId = "remote-reading-delete-link-timestamp",
                    sura = 6,
                    ayah = 8,
                    bookmarkId = "remote-reading-delete-link-bookmark",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val bookmark = collectionBookmarksRepository.getBookmarksForCollection(collectionId).single()
        removeBookmarkFromCollection(collectionId, bookmark)
        val linkDelete = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(linkDelete)
        )

        readingRepository.addAyahReadingBookmark(6, 8, at(200))
        assertTrue(readingRepository.deleteReadingBookmark())

        val bookmarkRow = database.bookmarksQueries
            .getBookmarkByRemoteId("remote-reading-delete-link-bookmark")
            .executeAsOne()
        val linkRow = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(linkDelete.localID.toLong())
            .executeAsOne()
        val derivedLinkDelete = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals("DELETED", linkRow.pending_op)
        assertEquals(bookmarkRow.modified_at, linkRow.modified_at)
        assertEquals(bookmarkRow.modified_at, derivedLinkDelete.model.lastUpdated.fromPlatform().toEpochMilliseconds())
    }

    @Test
    fun `imported reading bookmark tombstones displaced remote reading-only bookmark`() = runTest {
        persistDefaultCollection()
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
        val bookmark = collectionBookmarksRepository.getBookmarksForCollection(collectionId).single()
        removeBookmarkFromCollection(collectionId, bookmark)
        val staleCustomDelete = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()

        addBookmarkToCollection(collectionId, bookmark, at(200))
        collectionBookmarksRepository.applyRemoteChanges(
            updatesToPersist = emptyList(),
            localMutationsToClear = listOf(staleCustomDelete)
        )

        val row = database.bookmarksQueries.getBookmarkByRemoteId("remote-stale-custom-bookmark").executeAsOne()
        val link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(row.local_id, collectionId.toLong())
            .executeAsOne()
        val createMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(1L, link.is_active)
        assertEquals("CREATED", link.pending_op)
        assertEquals(200L, link.modified_at)
        assertEquals(Mutation.CREATED, createMutation.mutation)
        assertEquals(200L, createMutation.model.lastUpdated.fromPlatform().toEpochMilliseconds())
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
        val bookmark = collectionBookmarksRepository.getBookmarksForCollection(collectionId).single()
        removeBookmarkFromCollection(collectionId, bookmark)
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
        val bookmark = collectionBookmarksRepository.getBookmarksForCollection(collectionId).single()
        removeBookmarkFromCollection(collectionId, bookmark)
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
        val bookmark = collectionBookmarksRepository.getBookmarksForCollection(collectionId).single()
        removeBookmarkFromCollection(collectionId, bookmark)
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

        addBookmarkToCollection(collectionId, bookmark)

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
        persistDefaultCollection()
        val bookmark = readingRepository.addAyahReadingBookmark(8, 12, at(100))
        val readingCreate = bookmarksRepository.fetchMutatedBookmarks().single()

        PersistenceImportRepositoryImpl(database).importData(PersistenceImportData(), deleteExisting = true)

        val tombstoneBeforeAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.id.toLong())
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
            .getBookmarkByLocalId(bookmark.id.toLong())
            .executeAsOne()
        assertEquals("remote-import-reading-create", tombstoneAfterAck.remote_id)
        assertEquals(1L, tombstoneAfterAck.deleted)
        assertEquals("DELETED", tombstoneAfterAck.bookmark_pending_op)
        val deleteMutation = bookmarksRepository.fetchMutatedBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-import-reading-create", deleteMutation.remoteID)
    }


    @Test
    fun `deleteExisting import keeps pending custom link tombstone until link ACK binds`() = runTest {
        persistDefaultCollection()
        val collectionId = createCollection("ImportPendingCustom", "remote-import-pending-custom")
        seedBookmark(8, 14, listOf(collectionId), at(100))
        val customCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()

        PersistenceImportRepositoryImpl(database).importData(PersistenceImportData(), deleteExisting = true)

        val linkBeforeAck = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(customCreate.localID.toLong())
            .executeAsOne()
        val linkDeleteTimestamp = linkBeforeAck.modified_at
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
        assertEquals(linkDeleteTimestamp, linkAfterAck.modified_at)
        assertEquals("remote-import-custom-bookmark", linkAfterAck.last_synced_bookmark_remote_id)
        assertEquals("remote-import-pending-custom", linkAfterAck.last_synced_collection_remote_id)
        val deleteMutation = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-import-custom-bookmark", deleteMutation.model.bookmarkRemoteId)
        assertEquals("remote-import-pending-custom", deleteMutation.model.collectionRemoteId)
        assertEquals(linkDeleteTimestamp, deleteMutation.model.lastUpdated.fromPlatform().toEpochMilliseconds())
    }

    @Test
    fun `deleting collection preserves in-flight custom link create until create ACK binds delete`() = runTest {
        val collectionId = createCollection("PendingCustomDeleteCollection", "remote-pending-custom-delete-collection")
        seedBookmark(8, 15, listOf(collectionId), at(100))
        val customCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(customCreate.ack)))

        collectionsRepository.deleteCollection(collectionId)

        val linkBeforeAck = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(customCreate.localID.toLong())
            .executeAsOne()
        val linkDeleteTimestamp = linkBeforeAck.modified_at
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
        val linkAfterAck = database.bookmark_collectionsQueries
            .getCollectionBookmarkByLocalId(customCreate.localID.toLong())
            .executeAsOne()
        assertEquals(linkDeleteTimestamp, linkAfterAck.modified_at)
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-pending-custom-delete-collection-row", deleteMutation.model.bookmarkRemoteId)
        assertEquals("remote-pending-custom-delete-collection", deleteMutation.model.collectionRemoteId)
        assertEquals(linkDeleteTimestamp, deleteMutation.model.lastUpdated.fromPlatform().toEpochMilliseconds())
    }

    @Test
    fun `deleting collection removes never-pushed custom link create`() = runTest {
        val collectionId = createCollection("NeverPushedCustomDeleteCollection", "remote-never-pushed-delete-collection")
        val bookmark = seedBookmark(8, 16, listOf(collectionId), at(100))
        val bookmarkRow = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.id.toLong())
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
        val bookmark = seedBookmark(9, 1, listOf(collectionId), at(100))
        val customCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(customCreate.ack)))

        removeBookmarkFromCollection(collectionId, bookmark)

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
    fun `failed planning before custom link create remove leaves no unpushable tombstone`() = runTest {
        val collectionId = createCollection("FailedCustomRemove", "remote-failed-custom-remove")
        val bookmark = seedBookmark(9, 18, listOf(collectionId), at(100))
        val linkCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, linkCreate.mutation)

        removeBookmarkFromCollection(collectionId, bookmark)

        assertNull(
            database.bookmark_collectionsQueries
                .getCollectionBookmarkByLocalId(linkCreate.localID.toLong())
                .executeAsOneOrNull()
        )
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `failed push before custom link create remove leaves no unpushable tombstone`() = runTest {
        val collectionId = createCollection("FailedPushCustomRemove", "remote-failed-push-custom-remove")
        val bookmark = seedBookmark(9, 22, listOf(collectionId), at(100))
        val linkCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, linkCreate.mutation)

        removeBookmarkFromCollection(collectionId, bookmark)

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
        val bookmark = seedBookmark(9, 27, listOf(collectionId), at(100))
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

        removeBookmarkFromCollection(collectionId, bookmark)

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
        val bookmark = seedBookmark(9, 28, listOf(collectionId), at(100))
        val linkCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        val marked = collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(
            listOf(assertNotNull(linkCreate.ack))
        )

        removeBookmarkFromCollection(collectionId, bookmark)
        collectionBookmarksRepository.rollbackMutatedCollectionBookmarksInFlight(marked)

        assertNull(
            database.bookmark_collectionsQueries
                .getCollectionBookmarkByLocalId(linkCreate.localID.toLong())
                .executeAsOneOrNull()
        )
        assertNull(database.bookmarksQueries.getBookmarkByLocalId(bookmark.id.toLong()).executeAsOneOrNull())
        assertTrue(collectionBookmarksRepository.fetchMutatedCollectionBookmarks().none())
    }

    @Test
    fun `deleting in-flight reading-only create keeps bookmark tombstone until create ACK binds delete`() = runTest {
        val bookmark = readingRepository.addAyahReadingBookmark(9, 5, at(100))
        val readingCreate = bookmarksRepository.fetchMutatedBookmarks().single()
        bookmarksRepository.markMutatedBookmarksInFlight(listOf(assertNotNull(readingCreate.ack)))

        assertTrue(readingRepository.deleteReadingBookmark())

        val tombstoneBeforeAck = database.bookmarksQueries
            .getBookmarkByLocalId(bookmark.id.toLong())
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
            .getBookmarkByLocalId(bookmark.id.toLong())
            .executeAsOne()
        assertEquals("remote-pending-reading-create", tombstoneAfterAck.remote_id)
        assertEquals(1L, tombstoneAfterAck.deleted)
        assertEquals("DELETED", tombstoneAfterAck.bookmark_pending_op)
        val deleteMutation = bookmarksRepository.fetchMutatedBookmarks().single()
        assertEquals(Mutation.DELETED, deleteMutation.mutation)
        assertEquals("remote-pending-reading-create", deleteMutation.remoteID)
    }


    @Test
    fun `clearing saved reading after marker leaves newer final state pending`() = runTest {
        persistDefaultCollection()
        val bookmark = readingRepository.addAyahReadingBookmark(9, 22, at(100))
        seedBookmark(9, 22, at(125))
        val readingCreate = bookmarksRepository.fetchMutatedBookmarks().single {
            it.localID == bookmark.id
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
            .getBookmarkByLocalId(bookmark.id.toLong())
            .executeAsOne()
        assertEquals("remote-inflight-saved-reading", row.remote_id)
        assertEquals(0L, row.is_reading)
        assertEquals("CREATED", row.reading_pending_op)
        assertEquals(markedAck.observedPendingVersion + 1, row.reading_pending_version)

        val finalReadingState = bookmarksRepository.fetchMutatedBookmarks().single {
            it.localID == bookmark.id
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

        assertNull(database.bookmarksQueries.getBookmarkByLocalId(bookmark.id.toLong()).executeAsOneOrNull())
        assertTrue(bookmarksRepository.fetchMutatedBookmarks().none())
    }

    @Test
    fun `deleting never-pushed reading-only create removes local row`() = runTest {
        val bookmark = readingRepository.addAyahReadingBookmark(9, 20, at(100))
        val readingCreate = bookmarksRepository.fetchMutatedBookmarks().single()
        assertEquals(Mutation.CREATED, readingCreate.mutation)

        assertTrue(readingRepository.deleteReadingBookmark())

        assertNull(database.bookmarksQueries.getBookmarkByLocalId(bookmark.id.toLong()).executeAsOneOrNull())
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
        val bookmark = collectionBookmarksRepository.getBookmarksForCollection(collectionId).single()
        removeBookmarkFromCollection(collectionId, bookmark)
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
            .getBookmarkByLocalId(bookmark.id.toLong())
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
    fun `remote custom link create replay after local remove keeps link delete pending`() = runTest {
        val collectionId = createCollection("ReplayRemovedCustom", "remote-replayed-custom-collection")
        val bookmark = seedBookmark(10, 3, listOf(collectionId), at(100))
        val customCreate = collectionBookmarksRepository.fetchMutatedCollectionBookmarks().single()
        collectionBookmarksRepository.markMutatedCollectionBookmarksInFlight(listOf(assertNotNull(customCreate.ack)))

        removeBookmarkFromCollection(collectionId, bookmark)
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
            .getBookmarkByLocalId(bookmark.id.toLong())
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
        database.collectionsQueries.addNewCollection(name = name, timestamp = 1L, is_system = 0L)
        val collection = database.collectionsQueries.getCollectionByName(name).executeAsOne()
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = remoteId,
            name = name,
            modified_at = 1L,
            local_id = collection.local_id,
            is_default = 0L,
            is_system = 0L
        )
        return collection.local_id.toString()
    }

    private suspend fun seedBookmark(
        sura: Int,
        ayah: Int,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark {
        val defaultCollectionId = database.collectionsQueries
            .getDefaultCollection()
            .executeAsOne()
            .local_id
            .toString()
        return collectionBookmarksRepository.addAyahBookmarkToCollection(
            defaultCollectionId,
            sura,
            ayah,
            timestamp
        )
    }

    private suspend fun seedBookmark(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>
    ): CollectionAyahBookmark {
        return collectionIds
            .map { collectionId ->
                collectionBookmarksRepository.addAyahBookmarkToCollection(collectionId, sura, ayah)
            }
            .first()
    }

    private suspend fun seedBookmark(
        sura: Int,
        ayah: Int,
        collectionIds: List<String>,
        timestamp: PlatformDateTime
    ): CollectionAyahBookmark {
        return collectionIds
            .map { collectionId ->
                collectionBookmarksRepository.addAyahBookmarkToCollection(
                    collectionId,
                    sura,
                    ayah,
                    timestamp
                )
            }
            .first()
    }

    private suspend fun addBookmarkToCollection(
        collectionId: String,
        bookmark: CollectionAyahBookmark,
        timestamp: PlatformDateTime? = null
    ): CollectionAyahBookmark {
        return if (timestamp == null) {
            collectionBookmarksRepository.addAyahBookmarkToCollection(
                collectionId,
                bookmark.sura,
                bookmark.ayah
            )
        } else {
            collectionBookmarksRepository.addAyahBookmarkToCollection(
                collectionId,
                bookmark.sura,
                bookmark.ayah,
                timestamp
            )
        }
    }

    private suspend fun removeBookmarkFromCollection(
        collectionId: String,
        bookmark: CollectionAyahBookmark
    ): Boolean {
        val membership = collectionBookmarksRepository
            .getBookmarksForCollection(collectionId)
            .single { it.bookmarkId == bookmark.bookmarkId }
        return collectionBookmarksRepository.removeAyahBookmarkFromCollection(membership)
    }

    private fun persistDefaultCollection() {
        database.collectionsQueries.persistRemoteCollection(
            remote_id = "__default__",
            name = "Default",
            created_at = 1L,
            modified_at = 1L,
            is_default = 1L,
            is_system = 1L
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

private val CollectionAyahBookmark.id: String
    get() = bookmarkId

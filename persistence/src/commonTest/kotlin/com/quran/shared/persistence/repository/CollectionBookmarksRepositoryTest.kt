@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.RemoteCollectionBookmark
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class CollectionBookmarksRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: CollectionBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        repository = CollectionBookmarksRepositoryImpl(database)
    }

    @Test
    fun `applyRemoteChanges does not reset reading page bookmark when syncing collection bookmark`() = runTest {
        database.collectionsQueries.addNewCollection(name = "Recents")
        val collection = database.collectionsQueries.getCollectionByName("Recents").executeAsOne()
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = "remote-collection-id",
            name = collection.name,
            modified_at = 1L,
            local_id = collection.local_id
        )

        database.page_bookmarksQueries.addNewReadingBookmark(page = 10L)
        val existingPageBookmark = database.page_bookmarksQueries.getBookmarkForPage(10L).executeAsOne()
        assertEquals(1L, existingPageBookmark.is_reading)

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollectionBookmark.Page(
                        collectionId = "remote-collection-id",
                        page = 10,
                        lastUpdated = Instant.fromEpochMilliseconds(100L).toPlatform()
                    ),
                    remoteID = "remote-collection-bookmark-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val afterSync = database.page_bookmarksQueries.getBookmarkForPage(10L).executeAsOne()
        assertEquals(
            1L,
            afterSync.is_reading,
            "Existing reading page bookmark should stay reading after remote collection sync."
        )

        val collectionBookmark = database.bookmark_collectionsQueries
            .getCollectionBookmarksForCollectionWithDetails(collection_local_id = collection.local_id)
            .executeAsList()
            .single()
        assertEquals("remote-collection-bookmark-id", collectionBookmark.remote_id)
        assertEquals("PAGE", collectionBookmark.bookmark_type)
        assertEquals(afterSync.local_id.toString(), collectionBookmark.bookmark_local_id)
    }

    @Test
    fun `applyRemoteChanges inserts missing page bookmark as non-reading`() = runTest {
        database.collectionsQueries.addNewCollection(name = "ToSync")
        val collection = database.collectionsQueries.getCollectionByName("ToSync").executeAsOne()
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            remote_id = "remote-collection-id-2",
            name = collection.name,
            modified_at = 1L,
            local_id = collection.local_id
        )

        assertNull(database.page_bookmarksQueries.getBookmarkForPage(20L).executeAsOneOrNull())

        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteCollectionBookmark.Page(
                        collectionId = "remote-collection-id-2",
                        page = 20,
                        lastUpdated = Instant.fromEpochMilliseconds(200L).toPlatform()
                    ),
                    remoteID = "remote-collection-bookmark-id-2",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val createdBookmark = database.page_bookmarksQueries.getBookmarkForPage(20L).executeAsOne()
        assertEquals(
            0L,
            createdBookmark.is_reading,
            "Remote collection sync should insert missing page bookmarks as non-reading."
        )
    }
}

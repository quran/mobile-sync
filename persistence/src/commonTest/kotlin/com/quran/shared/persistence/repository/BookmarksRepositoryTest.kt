@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.persistence.util.QuranData
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class BookmarksRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: BookmarksRepositoryImpl
    private lateinit var syncRepository: BookmarksSynchronizationRepository

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        repository = BookmarksRepositoryImpl(database)
        syncRepository = repository
    }

    @Test
    fun `getAllBookmarks returns empty list when no bookmarks exist`() = runTest {
        assertTrue(repository.getAllBookmarks().isEmpty())
    }

    @Test
    fun `addBookmark stores an ayah bookmark`() = runTest {
        val bookmark = repository.addBookmark(2, 255)

        assertEquals(2, bookmark.sura)
        assertEquals(255, bookmark.ayah)
        assertEquals(1, repository.getAllBookmarks().size)
    }

    @Test
    fun `addBookmark does not duplicate the same ayah bookmark`() = runTest {
        repository.addBookmark(2, 255)
        repository.addBookmark(2, 255)

        val bookmarks = repository.getAllBookmarks()
        assertEquals(1, bookmarks.size)
        assertEquals(2, bookmarks.single().sura)
        assertEquals(255, bookmarks.single().ayah)
    }

    @Test
    fun `deleteBookmark removes local ayah bookmark`() = runTest {
        repository.addBookmark(2, 255)

        assertTrue(repository.deleteBookmark(2, 255))
        assertTrue(repository.getAllBookmarks().isEmpty())
    }

    @Test
    fun `fetchMutatedBookmarks returns created and deleted ayah bookmarks`() = runTest {
        repository.addBookmark(3, 2)

        database.ayah_bookmarksQueries.persistRemoteBookmark(
            remote_id = "remote-bookmark-id",
            ayah_id = QuranData.getAyahId(2, 255).toLong(),
            sura = 2L,
            ayah = 255L,
            created_at = 1L,
            modified_at = 1L
        )
        repository.deleteBookmark(2, 255)

        val mutations = syncRepository.fetchMutatedBookmarks()

        assertEquals(2, mutations.size)
        assertTrue(mutations.any { it.model.sura == 3 && it.model.ayah == 2 && it.mutation == Mutation.CREATED })
        assertTrue(mutations.any { it.model.sura == 2 && it.model.ayah == 255 && it.mutation == Mutation.DELETED })
    }

    @Test
    fun `applyRemoteChanges persists remote ayah bookmark and clears matching local mutation`() = runTest {
        val localBookmark = repository.addBookmark(2, 255)

        syncRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(
                        sura = 2,
                        ayah = 255,
                        isReading = false,
                        lastUpdated = Instant.fromEpochMilliseconds(1000L).toPlatform()
                    ),
                    remoteID = "remote-bookmark-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = listOf(
                LocalModelMutation(
                    model = localBookmark,
                    remoteID = null,
                    localID = localBookmark.localId,
                    mutation = Mutation.CREATED
                )
            )
        )

        val bookmark = database.ayah_bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-id").executeAsOneOrNull()
        assertNotNull(bookmark)
        assertEquals(2L, bookmark.sura)
        assertEquals(255L, bookmark.ayah)
        assertEquals(1, repository.getAllBookmarks().size)
    }

    @Test
    fun `applyRemoteChanges deletes remote ayah bookmark after local deletion`() = runTest {
        database.ayah_bookmarksQueries.persistRemoteBookmark(
            remote_id = "remote-bookmark-id",
            ayah_id = QuranData.getAyahId(2, 255).toLong(),
            sura = 2L,
            ayah = 255L,
            created_at = 1L,
            modified_at = 1L
        )
        repository.deleteBookmark(2, 255)

        val localMutations = syncRepository.fetchMutatedBookmarks()

        syncRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(
                        sura = 2,
                        ayah = 255,
                        isReading = false,
                        lastUpdated = Instant.fromEpochMilliseconds(2000L).toPlatform()
                    ),
                    remoteID = "remote-bookmark-id",
                    mutation = Mutation.DELETED
                )
            ),
            localMutationsToClear = localMutations
        )

        assertNull(database.ayah_bookmarksQueries.getBookmarkByRemoteId("remote-bookmark-id").executeAsOneOrNull())
    }
}

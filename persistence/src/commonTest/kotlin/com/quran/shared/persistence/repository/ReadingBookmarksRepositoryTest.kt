@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksSynchronizationRepository
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReadingBookmarksRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: ReadingBookmarksRepositoryImpl
    private lateinit var syncRepository: ReadingBookmarksSynchronizationRepository

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        repository = ReadingBookmarksRepositoryImpl(database)
        syncRepository = repository
    }

    @Test
    fun `getReadingBookmark returns null when no bookmark exists`() = runTest {
        assertNull(repository.getReadingBookmark())
    }

    @Test
    fun `addAyahReadingBookmark stores a single reading bookmark`() = runTest {
        val bookmark = repository.addAyahReadingBookmark(2, 255)

        assertEquals(2, bookmark.sura)
        assertEquals(255, bookmark.ayah)
        assertEquals(1, database.reading_bookmarksQueries.getReadingBookmarks().executeAsList().size)
    }

    @Test
    fun `addAyahReadingBookmark replaces the previous reading bookmark`() = runTest {
        repository.addAyahReadingBookmark(2, 255)
        repository.addAyahReadingBookmark(3, 2)

        val bookmark = repository.getReadingBookmark() as AyahReadingBookmark
        assertEquals(3, bookmark.sura)
        assertEquals(2, bookmark.ayah)
        assertEquals(1, database.reading_bookmarksQueries.getReadingBookmarks().executeAsList().size)
    }

    @Test
    fun `addPageReadingBookmark replaces an ayah reading bookmark`() = runTest {
        repository.addAyahReadingBookmark(2, 255)
        repository.addPageReadingBookmark(42)

        val bookmark = repository.getReadingBookmark() as PageReadingBookmark
        assertEquals(42, bookmark.page)
        assertEquals(1, database.reading_bookmarksQueries.getReadingBookmarks().executeAsList().size)
    }

    @Test
    fun `addAyahReadingBookmark replaces a page reading bookmark`() = runTest {
        repository.addPageReadingBookmark(42)
        repository.addAyahReadingBookmark(3, 2)

        val bookmark = repository.getReadingBookmark() as AyahReadingBookmark
        assertEquals(3, bookmark.sura)
        assertEquals(2, bookmark.ayah)
        assertEquals(1, database.reading_bookmarksQueries.getReadingBookmarks().executeAsList().size)
    }

    @Test
    fun `deleteReadingBookmark removes the current reading bookmark`() = runTest {
        repository.addAyahReadingBookmark(2, 255)

        assertTrue(repository.deleteReadingBookmark())
        assertNull(repository.getReadingBookmark())
    }

    @Test
    fun `fetchMutatedReadingBookmarks keeps only created mutation when replacing unsynced reading bookmark`() = runTest {
        repository.addAyahReadingBookmark(2, 255)
        repository.addAyahReadingBookmark(3, 2)

        val mutations = syncRepository.fetchMutatedReadingBookmarks()

        assertEquals(1, mutations.size)
        assertTrue(
            mutations.any {
                val model = it.model as? AyahReadingBookmark
                model != null &&
                    model.sura == 3 &&
                    model.ayah == 2 &&
                    it.mutation == Mutation.CREATED
            }
        )
    }

    @Test
    fun `fetchMutatedReadingBookmarks includes deletion for synced bookmark when replaced`() = runTest {
        syncRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(
                        sura = 2,
                        ayah = 255,
                        isReading = false,
                        lastUpdated = Instant.fromEpochMilliseconds(1000L).toPlatform()
                    ),
                    remoteID = "remote-reading-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        repository.addAyahReadingBookmark(3, 2)
        val mutations = syncRepository.fetchMutatedReadingBookmarks()

        assertTrue(
            mutations.any {
                val model = it.model as? AyahReadingBookmark
                model != null &&
                    model.sura == 2 && model.ayah == 255 &&
                    it.mutation == Mutation.DELETED &&
                    it.remoteID == "remote-reading-id"
            }
        )
        assertTrue(
            mutations.any {
                val model = it.model as? AyahReadingBookmark
                model != null &&
                    model.sura == 3 && model.ayah == 2 &&
                    it.mutation == Mutation.CREATED &&
                    it.remoteID == null
            }
        )
    }

    @Test
    fun `applyRemoteChanges persists remote reading bookmark without local row`() = runTest {
        syncRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(
                        sura = 2,
                        ayah = 255,
                        isReading = false,
                        lastUpdated = Instant.fromEpochMilliseconds(1000L).toPlatform()
                    ),
                    remoteID = "remote-reading-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList<LocalModelMutation<ReadingBookmark>>()
        )

        val bookmark = repository.getReadingBookmark() as AyahReadingBookmark
        assertNotNull(bookmark)
        assertEquals(2, bookmark.sura)
        assertEquals(255, bookmark.ayah)
        assertEquals("remote-reading-id", database.reading_bookmarksQueries.getReadingBookmarkByRemoteId("remote-reading-id").executeAsOne().remote_id)
    }

    @Test
    fun `applyRemoteChanges persists remote page reading bookmark without local row`() = runTest {
        syncRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Page(
                        page = 42,
                        isReading = true,
                        lastUpdated = Instant.fromEpochMilliseconds(1000L).toPlatform()
                    ),
                    remoteID = "remote-page-reading-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val bookmark = repository.getReadingBookmark() as PageReadingBookmark
        assertNotNull(bookmark)
        assertEquals(42, bookmark.page)
        assertEquals("remote-page-reading-id", database.reading_bookmarksQueries.getReadingBookmarkByRemoteId("remote-page-reading-id").executeAsOne().remote_id)
    }

    @Test
    fun `applyRemoteChanges updates existing remote reading bookmark without duplicating rows`() = runTest {
        syncRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Ayah(
                        sura = 2,
                        ayah = 255,
                        isReading = true,
                        lastUpdated = Instant.fromEpochMilliseconds(1000L).toPlatform()
                    ),
                    remoteID = "remote-reading-id",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )

        syncRepository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteBookmark.Page(
                        page = 42,
                        isReading = true,
                        lastUpdated = Instant.fromEpochMilliseconds(2000L).toPlatform()
                    ),
                    remoteID = "remote-reading-id",
                    mutation = Mutation.MODIFIED
                )
            ),
            localMutationsToClear = emptyList()
        )

        val bookmark = repository.getReadingBookmark() as PageReadingBookmark
        assertNotNull(bookmark)
        assertEquals(42, bookmark.page)
        assertEquals(1, database.reading_bookmarksQueries.getReadingBookmarks().executeAsList().size)
    }
}

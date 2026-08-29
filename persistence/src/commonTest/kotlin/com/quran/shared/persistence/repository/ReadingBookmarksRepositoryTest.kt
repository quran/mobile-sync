@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.RemoteReadingBookmark
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.EmptyReadingBookmark
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class ReadingBookmarksRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: ReadingBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        repository = ReadingBookmarksRepositoryImpl(database)
    }

    @Test
    fun `three slots support only ayah and page locations`() = runTest {
        repository.setAyahReadingBookmark(slot = 1, sura = 2, ayah = 255)
        repository.setPageReadingBookmark(slot = 2, page = 42)
        repository.renameReadingBookmark(slot = 3, name = "Resume")

        val bookmarks = repository.getReadingBookmarks()
        val ayah = assertIs<AyahReadingBookmark>(bookmarks[0])
        val page = assertIs<PageReadingBookmark>(bookmarks[1])
        val empty = assertIs<EmptyReadingBookmark>(bookmarks[2])

        assertEquals(1, ayah.slot)
        assertEquals(255, ayah.ayah)
        assertEquals(2, page.slot)
        assertEquals(42, page.page)
        assertEquals("Resume", empty.name)
        assertEquals(1L, database.reading_bookmarksQueries.getReadingBookmarkForSlot(1).executeAsOne().mushaf_id)
        assertEquals(1L, database.reading_bookmarksQueries.getReadingBookmarkForSlot(2).executeAsOne().mushaf_id)
        assertEquals(0L, database.bookmarksQueries.countAll().executeAsOne())
    }

    @Test
    fun `clearing a slot produces a synced empty slot without deleting its identity`() = runTest {
        repository.setPageReadingBookmark(slot = 2, page = 88)
        val before = repository.fetchMutatedReadingBookmarks().single()
        val rowBefore = database.reading_bookmarksQueries.getReadingBookmarkForSlot(2).executeAsOne()
        database.reading_bookmarksQueries.persistRemoteReadingBookmark(
            remote_id = "remote-slot-2",
            slot = 2,
            name = rowBefore.name,
            bookmark_type = rowBefore.bookmark_type,
            sura = rowBefore.sura,
            ayah = rowBefore.ayah,
            page = rowBefore.page,
            mushaf_id = rowBefore.mushaf_id,
            created_at = rowBefore.created_at,
            modified_at = rowBefore.modified_at
        )

        val cleared = assertIs<EmptyReadingBookmark>(repository.clearReadingBookmark(2))
        val mutation = repository.fetchMutatedReadingBookmarks().single()

        assertEquals(before.localID, cleared.id)
        assertEquals(Mutation.MODIFIED, mutation.mutation)
        assertEquals("remote-slot-2", mutation.remoteID)
        assertNull(mutation.model.type)
        assertNull(database.reading_bookmarksQueries.getReadingBookmarkForSlot(2).executeAsOne().mushaf_id)
    }

    @Test
    fun `remote reading bookmark persists in its slot`() = runTest {
        repository.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteReadingBookmark(
                        slot = 3,
                        name = "Study",
                        type = "AYAH",
                        sura = 18,
                        ayah = 10,
                        page = null,
                        lastUpdated = Instant.fromEpochMilliseconds(200).toPlatform(),
                        createdAt = Instant.fromEpochMilliseconds(100).toPlatform()
                    ),
                    remoteID = "remote-slot-3",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList(),
            writeBoundaryGuard = PersistenceWriteBoundaryGuard.Allow
        )

        val bookmark = assertIs<AyahReadingBookmark>(repository.getReadingBookmarks().single())
        assertEquals(3, bookmark.slot)
        assertEquals("Study", bookmark.name)
        assertEquals(1L, database.reading_bookmarksQueries.getReadingBookmarkForSlot(3).executeAsOne().mushaf_id)
        assertEquals(emptyList(), repository.fetchMutatedReadingBookmarks())
    }

    @Test
    fun `slot outside one through three is rejected`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            repository.setPageReadingBookmark(slot = 4, page = 1)
        }
    }

    @Test
    fun `explicit timestamps are stored for every reading bookmark mutation`() = runTest {
        repository.setAyahReadingBookmark(
            slot = 1,
            sura = 2,
            ayah = 255,
            timestamp = Instant.fromEpochMilliseconds(100).toPlatform()
        )
        assertEquals(100L, database.reading_bookmarksQueries.getReadingBookmarkForSlot(1).executeAsOne().modified_at)

        repository.setPageReadingBookmark(
            slot = 2,
            page = 42,
            timestamp = Instant.fromEpochMilliseconds(200).toPlatform()
        )
        assertEquals(200L, database.reading_bookmarksQueries.getReadingBookmarkForSlot(2).executeAsOne().modified_at)

        repository.renameReadingBookmark(
            slot = 1,
            name = "Resume",
            timestamp = Instant.fromEpochMilliseconds(300).toPlatform()
        )
        assertEquals(300L, database.reading_bookmarksQueries.getReadingBookmarkForSlot(1).executeAsOne().modified_at)

        repository.clearReadingBookmark(
            slot = 2,
            timestamp = Instant.fromEpochMilliseconds(400).toPlatform()
        )
        assertEquals(400L, database.reading_bookmarksQueries.getReadingBookmarkForSlot(2).executeAsOne().modified_at)
    }

    @Test
    fun `storage accepts future positive slots`() {
        database.reading_bookmarksQueries.setPageReadingBookmark(
            slot = 5,
            page = 42,
            mushaf_id = 1,
            timestamp = 100
        )

        assertEquals(5L, database.reading_bookmarksQueries.getReadingBookmarkForSlot(5).executeAsOne().slot)
    }

}

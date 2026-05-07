@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.persistence.repository

import com.quran.shared.mutations.Mutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.ImportAyahBookmark
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class PersistenceImportRepositoryTest {
    private lateinit var database: QuranDatabase
    private lateinit var repository: PersistenceImportRepositoryImpl

    @BeforeTest
    fun setup() {
        database = QuranDatabase(TestDatabaseDriver().createDriver())
        repository = PersistenceImportRepositoryImpl(database)
    }

    @Test
    fun `importData imports a complete data set into an empty database`() = runTest {
        val result = repository.importData(
            PersistenceImportData(
                bookmarks = listOf(
                    ImportAyahBookmark("bookmark-1", 2, 255, timestamp(1_000)),
                    ImportAyahBookmark("bookmark-2", 3, 2, timestamp(2_000))
                ),
                collections = listOf(
                    ImportCollection("collection-1", "Favorites", timestamp(3_000))
                ),
                collectionBookmarks = listOf(
                    ImportCollectionAyahBookmark("collection-1", "bookmark-1", timestamp(4_000))
                ),
                readingSessions = listOf(
                    ImportReadingSession(4, 1, timestamp(6_000)),
                    ImportReadingSession(5, 1, timestamp(5_000))
                ),
                readingBookmark = ImportReadingBookmark.Page(
                    page = 42,
                    lastUpdated = timestamp(7_000)
                ),
                notes = listOf(
                    ImportNote(
                        body = "Important note",
                        startSura = 2,
                        startAyah = 255,
                        endSura = 2,
                        endAyah = 257,
                        lastUpdated = timestamp(8_000)
                    )
                )
            )
        )

        assertEquals(2, result.bookmarksImported)
        assertEquals(1, result.collectionsImported)
        assertEquals(1, result.collectionBookmarksImported)
        assertEquals(2, result.readingSessionsImported)
        assertTrue(result.readingBookmarkImported)
        assertEquals(1, result.notesImported)

        val bookmarksRepository = BookmarksRepositoryImpl(database)
        val collectionsRepository = CollectionsRepositoryImpl(database)
        val collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(database)
        val readingSessionsRepository = ReadingSessionsRepositoryImpl(database)
        val readingBookmarksRepository = ReadingBookmarksRepositoryImpl(database)
        val notesRepository = NotesRepositoryImpl(database)

        val bookmarks = bookmarksRepository.getAllBookmarks()
        assertEquals(2, bookmarks.size)
        assertTrue(bookmarks.any { it.sura == 2 && it.ayah == 255 })
        assertTrue(bookmarks.any { it.sura == 3 && it.ayah == 2 })

        val collections = collectionsRepository.getAllCollections()
        assertEquals(1, collections.size)
        assertEquals("Favorites", collections.single().name)

        val collectionBookmarks = collectionBookmarksRepository.getBookmarksForCollection(collections.single().localId)
        assertEquals(1, collectionBookmarks.size)
        assertEquals(2, collectionBookmarks.single().sura)
        assertEquals(255, collectionBookmarks.single().ayah)

        val readingSessions = readingSessionsRepository.getReadingSessions()
        assertEquals(2, readingSessions.size)
        assertEquals(4, readingSessions.first().sura)
        assertEquals(1, readingSessions.first().ayah)

        val readingBookmark = readingBookmarksRepository.getReadingBookmark() as PageReadingBookmark
        assertEquals(42, readingBookmark.page)

        val notes = notesRepository.getAllNotes()
        assertEquals(1, notes.size)
        assertEquals("Important note", notes.single().body)
        assertEquals(2, notes.single().startSura)
        assertEquals(255, notes.single().startAyah)
    }

    @Test
    fun `importData exposes imported rows as local created mutations`() = runTest {
        repository.importData(
            PersistenceImportData(
                bookmarks = listOf(ImportAyahBookmark("bookmark-1", 2, 255, timestamp(1_000))),
                collections = listOf(ImportCollection("collection-1", "Favorites", timestamp(2_000))),
                collectionBookmarks = listOf(
                    ImportCollectionAyahBookmark("collection-1", "bookmark-1", timestamp(3_000))
                ),
                readingSessions = listOf(ImportReadingSession(3, 2, timestamp(4_000))),
                readingBookmark = ImportReadingBookmark.Ayah(4, 4, timestamp(5_000)),
                notes = listOf(
                    ImportNote("Note", 2, 1, 2, 2, timestamp(6_000))
                )
            )
        )

        val bookmarkMutations = BookmarksRepositoryImpl(database).fetchMutatedBookmarks()
        assertEquals(1, bookmarkMutations.size)
        assertEquals(Mutation.CREATED, bookmarkMutations.single().mutation)

        val collectionMutations = CollectionsRepositoryImpl(database).fetchMutatedCollections()
        assertEquals(1, collectionMutations.size)
        assertEquals(Mutation.CREATED, collectionMutations.single().mutation)

        val readingSessionMutations = ReadingSessionsRepositoryImpl(database).fetchMutatedReadingSessions()
        assertEquals(1, readingSessionMutations.size)
        assertEquals(Mutation.CREATED, readingSessionMutations.single().mutation)

        val readingBookmarkMutations = ReadingBookmarksRepositoryImpl(database).fetchMutatedReadingBookmarks()
        assertEquals(1, readingBookmarkMutations.size)
        assertEquals(Mutation.CREATED, readingBookmarkMutations.single().mutation)

        val noteMutations = NotesRepositoryImpl(database).fetchMutatedNotes(0)
        assertEquals(1, noteMutations.size)
        assertEquals(Mutation.CREATED, noteMutations.single().mutation)

        val collection = database.collectionsQueries.getCollectionByName("Favorites").executeAsOne()
        database.collectionsQueries.updateRemoteCollectionByLocalId(
            local_id = collection.local_id,
            remote_id = "remote-collection-id",
            name = collection.name,
            modified_at = 7_000
        )

        val collectionBookmarkMutations = CollectionBookmarksRepositoryImpl(database)
            .fetchMutatedCollectionBookmarks()
        assertEquals(1, collectionBookmarkMutations.size)
        assertEquals(Mutation.CREATED, collectionBookmarkMutations.single().mutation)
    }

    @Test
    fun `importData fails when the database is not empty`() = runTest {
        BookmarksRepositoryImpl(database).addBookmark(2, 255)

        assertFailsWith<IllegalStateException> {
            repository.importData(
                PersistenceImportData(
                    collections = listOf(ImportCollection("collection-1", "Favorites", timestamp(1_000)))
                )
            )
        }

        assertEquals(1L, database.ayah_bookmarksQueries.countAll().executeAsOne())
        assertEquals(0L, database.collectionsQueries.countAll().executeAsOne())
    }

    @Test
    fun `importData validates relationship targets before inserting rows`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            repository.importData(
                PersistenceImportData(
                    bookmarks = listOf(ImportAyahBookmark("bookmark-1", 2, 255, timestamp(1_000))),
                    collections = listOf(ImportCollection("collection-1", "Favorites", timestamp(2_000))),
                    collectionBookmarks = listOf(
                        ImportCollectionAyahBookmark("collection-1", "missing-bookmark", timestamp(3_000))
                    )
                )
            )
        }

        assertDatabaseEmpty()
    }

    @Test
    fun `importData validates ayah values before inserting rows`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            repository.importData(
                PersistenceImportData(
                    bookmarks = listOf(
                        ImportAyahBookmark("bookmark-1", 2, 255, timestamp(1_000)),
                        ImportAyahBookmark("bookmark-2", 115, 1, timestamp(2_000))
                    )
                )
            )
        }

        assertDatabaseEmpty()
    }

    private fun assertDatabaseEmpty() {
        assertEquals(0L, database.ayah_bookmarksQueries.countAll().executeAsOne())
        assertEquals(0L, database.reading_bookmarksQueries.countAll().executeAsOne())
        assertEquals(0L, database.collectionsQueries.countAll().executeAsOne())
        assertEquals(0L, database.bookmark_collectionsQueries.countAll().executeAsOne())
        assertEquals(0L, database.notesQueries.countAll().executeAsOne())
        assertEquals(0L, database.reading_sessionsQueries.countAll().executeAsOne())
    }

    private fun timestamp(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()
}

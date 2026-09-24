package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.ImportAyahHighlight
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.RemoteNote
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ImportMergeTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: QuranDatabase
    private lateinit var repository: PersistenceImportRepositoryImpl

    @BeforeTest
    fun setup() {
        driver = TestDatabaseDriver().createDriver()
        database = QuranDatabase(driver)
        repository = PersistenceImportRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `import accepts the same raw values as repository writes`() = runTest {
        val result = repository.importData(
            PersistenceImportData(
                collections = listOf(
                    ImportCollection("blank-name", "", at(100)),
                    ImportCollection("", "Ignored duplicate ID", at(100)),
                    ImportCollection("", "Selected duplicate ID", at(200))
                ),
                collectionBookmarks = listOf(
                    ImportCollectionAyahBookmark("", 0, 0, at(200))
                ),
                readingSessions = listOf(ImportReadingSession(115, 0, at(200))),
                notes = listOf(ImportNote("", 115, 1, -1, 0, at(200))),
                highlights = listOf(
                    ImportAyahHighlight(-1, -1, AyahHighlightColor.BLUE, at(200))
                )
            )
        )

        assertTrue(result.changed)
        assertEquals(2, result.collectionsImported)
        assertEquals(2, result.bookmarksImported)
        assertEquals(1, result.collectionBookmarksImported)
        assertEquals(1, result.readingSessionsImported)
        assertEquals(1, result.notesImported)
        assertEquals(1, result.highlightsImported)
        assertEquals("", database.collectionsQueries.getCollectionByName("").executeAsOne().name)
        assertEquals(
            "Selected duplicate ID",
            database.collectionsQueries.getCollectionByName("Selected duplicate ID").executeAsOne().name
        )
        assertEquals(null, database.collectionsQueries.getCollectionByName("Ignored duplicate ID").executeAsOneOrNull())
        assertEquals("", database.notesQueries.getNotes().executeAsOne().note)
        assertEquals(
            115L to 0L,
            database.reading_sessionsQueries.getReadingSessions().executeAsOne().let {
                it.chapter_number to it.verse_number
            }
        )
        assertTrue(database.bookmarksQueries.getBookmarkForAyah(0, 0).executeAsOne().local_id > 0)
        assertTrue(database.bookmarksQueries.getBookmarkForAyah(-1, -1).executeAsOne().local_id > 0)
    }

    @Test
    fun `reactivating a retained membership is a mutation but not an insertion`() = runTest {
        val data = PersistenceImportData(
            collections = listOf(ImportCollection("study", "Study", at(100))),
            collectionBookmarks = listOf(
                ImportCollectionAyahBookmark("study", 2, 255, at(100))
            )
        )
        repository.importData(data)
        val bookmark = database.bookmarksQueries.getBookmarkForAyah(2, 255).executeAsOne()
        val collection = database.collectionsQueries.getCollectionByName("Study").executeAsOne()
        var link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(bookmark.local_id, collection.local_id)
            .executeAsOne()
        database.bookmark_collectionsQueries.persistRemoteBookmarkCollection(
            bookmark_local_id = bookmark.local_id,
            collection_local_id = collection.local_id,
            bookmark_remote_id = "remote-bookmark",
            collection_remote_id = "remote-collection",
            created_at = link.created_at,
            modified_at = link.modified_at
        )
        database.bookmark_collectionsQueries.clearLocalMutationFor(
            bookmark_remote_id = "remote-bookmark",
            collection_remote_id = "remote-collection",
            modified_at = link.modified_at,
            id = link.local_id,
            pending_op = link.pending_op,
            pending_version = link.pending_version
        )
        database.bookmark_collectionsQueries.markBookmarkCollectionDeleted(
            bookmark_local_id = bookmark.local_id,
            collection_local_id = collection.local_id,
            timestamp = 200
        )
        link = database.bookmark_collectionsQueries
            .getCollectionBookmarkFor(bookmark.local_id, collection.local_id)
            .executeAsOne()
        assertEquals(0L, link.is_active)

        val result = repository.importData(data, deleteExisting = false)

        assertEquals(0, result.collectionBookmarksImported)
        assertTrue(result.changed)
        assertEquals(
            1L,
            database.bookmark_collectionsQueries
                .getCollectionBookmarkFor(bookmark.local_id, collection.local_id)
                .executeAsOne()
                .is_active
        )
    }

    @Test
    fun `existing target highlight wins over imported colors`() = runTest {
        CollectionBookmarksRepositoryImpl(database).setHighlight(2, 255, AyahHighlightColor.BLUE, at(100))

        val result = repository.importData(
            PersistenceImportData(
                highlights = listOf(
                    ImportAyahHighlight(2, 255, AyahHighlightColor.BLUE, at(200)),
                    ImportAyahHighlight(2, 255, AyahHighlightColor.RED, at(300))
                )
            )
        )

        assertEquals(1, result.matched)
        assertEquals(1, result.keptExisting)
        assertEquals(0, result.highlightsImported)
        assertFalse(result.changed)
        assertEquals(
            listOf("system:highlights:blue"),
            database.bookmark_collectionsQueries.getCollectionBookmarksWithDetails().executeAsList()
                .map { it.collection_name }
        )
    }

    @Test
    fun `note matches the stored first range of a remote note`() = runTest {
        val notes = NotesRepositoryImpl(database)
        // The sync pipeline stores the first range of a multi-range remote note locally.
        notes.applyRemoteChanges(
            updatesToPersist = listOf(
                RemoteModelMutation(
                    model = RemoteNote("Remote  text", 2, 1, 2, 3, at(100)),
                    remoteID = "remote-note",
                    mutation = Mutation.CREATED
                )
            ),
            localMutationsToClear = emptyList()
        )
        val remote = database.notesQueries.getNoteByRemoteId("remote-note").executeAsOne()

        val result = repository.importData(
            PersistenceImportData(notes = listOf(ImportNote("Remote text", 2, 1, 2, 3, at(200))))
        )

        assertEquals(1, result.matched)
        assertEquals(0, result.notesImported)
        assertFalse(result.changed)
        assertEquals(listOf(remote), database.notesQueries.getNotes().executeAsList())
        assertTrue(notes.fetchMutatedNotes(lastModified = 0).isEmpty())
    }

    @Test
    fun `new parent bookmark dates come from winning highlights only`() = runTest {
        repository.importData(
            PersistenceImportData(
                highlights = listOf(
                    ImportAyahHighlight(2, 255, AyahHighlightColor.BLUE, at(100), at(10)),
                    ImportAyahHighlight(2, 255, AyahHighlightColor.YELLOW, at(200), at(50))
                )
            )
        )

        val bookmark = database.bookmarksQueries.getBookmarkForAyah(2, 255).executeAsOne()
        assertEquals(50L, bookmark.created_at)
        assertEquals(200L, bookmark.modified_at)
    }

    @Test
    fun `first saved membership on a highlight-only parent stamps the bookmark`() = runTest {
        repository.importData(
            PersistenceImportData(
                highlights = listOf(ImportAyahHighlight(2, 255, AyahHighlightColor.BLUE, at(100)))
            )
        )

        repository.importData(
            PersistenceImportData(
                collections = listOf(
                    ImportCollection("study", "Study", at(250)),
                    ImportCollection("review", "Review", at(300))
                ),
                collectionBookmarks = listOf(
                    ImportCollectionAyahBookmark("study", 2, 255, at(250)),
                    ImportCollectionAyahBookmark("review", 2, 255, at(300))
                )
            )
        )

        val bookmark = database.bookmarksQueries.getBookmarkForAyah(2, 255).executeAsOne()
        assertEquals(100L, bookmark.created_at)
        assertEquals(300L, bookmark.modified_at)
        assertEquals(300L, bookmark.bookmark_modified_at)
    }

    @Test
    fun `later saved memberships leave an already saved bookmark unchanged`() = runTest {
        val study = PersistenceImportData(
            collections = listOf(ImportCollection("study", "Study", at(100))),
            collectionBookmarks = listOf(ImportCollectionAyahBookmark("study", 2, 255, at(100)))
        )
        repository.importData(study)
        val saved = database.bookmarksQueries.getBookmarkForAyah(2, 255).executeAsOne()

        repository.importData(
            PersistenceImportData(
                collections = listOf(ImportCollection("review", "Review", at(400))),
                collectionBookmarks = listOf(ImportCollectionAyahBookmark("review", 2, 255, at(400)))
            )
        )

        assertEquals(saved, database.bookmarksQueries.getBookmarkForAyah(2, 255).executeAsOne())
    }

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis).toPlatform()
}

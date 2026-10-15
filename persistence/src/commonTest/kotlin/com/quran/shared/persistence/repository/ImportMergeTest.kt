package com.quran.shared.persistence.repository

import com.quran.shared.persistence.MobileSyncDatabase
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.input.ImportAyahHighlight
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.model.AyahHighlight
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.importdata.PersistenceImportRepositoryImpl
import com.quran.shared.persistence.repository.note.NoteStore
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.syncv2.types.NoteRange
import com.quran.shared.syncv2.types.NotesType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportMergeTest {
    private lateinit var database: MobileSyncDatabase
    private lateinit var repository: PersistenceImportRepositoryImpl
    private lateinit var collectionBookmarks: CollectionBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        database = createTestDatabase()
        repository = PersistenceImportRepositoryImpl(database.quran, database.synced)
        collectionBookmarks = CollectionBookmarksRepositoryImpl(database.synced)
    }

    @Test
    fun `import accepts the same raw values as repository writes`() = runTest {
        val result = repository.importData(
            PersistenceImportData(
                collections = listOf(ImportCollection("", at(200))),
                collectionBookmarks = listOf(ImportCollectionAyahBookmark("", 0, 0, at(200))),
                readingSessions = listOf(ImportReadingSession(115, 0, at(200))),
                notes = listOf(ImportNote("", 115, 1, -1, 0, at(200))),
                highlights = listOf(ImportAyahHighlight(-1, -1, AyahHighlightColor.BLUE, at(200)))
            )
        )

        assertTrue(result.changed)
        assertEquals(1, result.collectionsImported)
        assertEquals(1, result.bookmarksImported)
        assertEquals(1, result.collectionBookmarksImported)
        assertEquals(1, result.readingSessionsImported)
        assertEquals(1, result.notesImported)
        assertEquals(1, result.highlightsImported)
        assertEquals("", database.synced.collectionsQueries.collectionByKey("").executeAsOne().name)
        assertEquals("", database.synced.notesQueries.all().executeAsOne().body)
        assertEquals(115L to 0L, database.synced.sessionsQueries.all().executeAsOne().let { it.sura to it.ayah })
        assertEquals(1, verseMemberships(0, 0).size)
        assertEquals(listOf(-1 to -1), collectionBookmarks.getHighlightsFlow().first().map { it.sura to it.ayah })
    }

    @Test
    fun `existing target highlight wins over imported colors`() = runTest {
        collectionBookmarks.setHighlight(2, 255, AyahHighlightColor.BLUE, at(100))

        val result = repository.importData(
            PersistenceImportData(
                highlights = listOf(
                    ImportAyahHighlight(2, 255, AyahHighlightColor.BLUE, at(200)),
                    ImportAyahHighlight(2, 255, AyahHighlightColor.PINK, at(300))
                )
            )
        )

        assertEquals(1, result.matched)
        assertEquals(1, result.keptExisting)
        assertEquals(0, result.highlightsImported)
        assertFalse(result.changed)
        assertEquals(
            listOf(AyahHighlight(2, 255, AyahHighlightColor.BLUE, at(100))),
            collectionBookmarks.getHighlightsFlow().first()
        )
    }

    @Test
    fun `imported highlights keep one color per verse`() = runTest {
        val result = repository.importData(
            PersistenceImportData(
                highlights = listOf(
                    ImportAyahHighlight(2, 255, AyahHighlightColor.BLUE, at(100), at(10)),
                    ImportAyahHighlight(2, 255, AyahHighlightColor.YELLOW, at(200), at(50))
                )
            )
        )

        assertEquals(1, result.highlightsImported)
        assertEquals(1, verseMemberships(2, 255).size)
        assertEquals(
            listOf(AyahHighlight(2, 255, AyahHighlightColor.YELLOW, at(200))),
            collectionBookmarks.getHighlightsFlow().first()
        )
    }

    @Test
    fun `note on one range doesn't match a note with several that starts with it`() = runTest {
        insertNote("Remote text", listOf(NoteRange(2, 1, 2, 3), NoteRange(3, 1, 3, 2)), 100)

        val result = repository.importData(
            PersistenceImportData(notes = listOf(ImportNote("Remote text", 2, 1, 2, 3, at(200))))
        )

        assertEquals(0, result.matched)
        assertEquals(1, result.notesImported)
        assertEquals(2, database.synced.notesQueries.all().executeAsList().size)
    }

    @Test
    fun `note matches a note on the same range whose body differs only in surrounding whitespace`() = runTest {
        insertNote("Remote text", listOf(NoteRange(2, 1, 2, 3)), 100)
        val before = database.synced.notesQueries.all().executeAsList()

        // Trimmed like the server: JavaScript trims the byte order mark, which Kotlin's trim() keeps.
        val result = repository.importData(
            PersistenceImportData(notes = listOf(ImportNote("\uFEFF Remote text\n", 2, 1, 2, 3, at(200))))
        )

        assertEquals(1, result.matched)
        assertEquals(0, result.notesImported)
        assertFalse(result.changed)
        assertEquals(before, database.synced.notesQueries.all().executeAsList())
    }

    @Test
    fun `note doesn't match a note whose body differs in internal whitespace`() = runTest {
        insertNote("Remote  text", listOf(NoteRange(2, 1, 2, 3)), 100)

        val result = repository.importData(
            PersistenceImportData(notes = listOf(ImportNote("Remote text", 2, 1, 2, 3, at(200))))
        )

        assertEquals(0, result.matched)
        assertEquals(1, result.notesImported)
        assertEquals(
            listOf("Remote  text", "Remote text"),
            database.synced.notesQueries.all().executeAsList().map { it.body }.sorted()
        )
    }

    @Test
    fun `note matches an archived note`() = runTest {
        val id = insertNote("Archived text", listOf(NoteRange(2, 1, 2, 3)), 100)
        val row = database.synced.notesQueries.all().executeAsOne()
        database.synced.notesQueries.update(row.body, row.created_at, row.updated_at, 150, id)

        val result = repository.importData(
            PersistenceImportData(notes = listOf(ImportNote("Archived text", 2, 1, 2, 3, at(200))))
        )

        assertEquals(1, result.matched)
        assertEquals(0, result.notesImported)
    }

    @Test
    fun `import skips exactly the notes sync would link`() = runTest {
        val kursi = NoteRange(2, 255, 2, 255)
        val imported = ImportNote("Ayat al-Kursi", 2, 255, 2, 255, at(200))
        // An existing note on the device, and whether it is the imported note.
        val cases = listOf(
            Triple("Ayat al-Kursi", listOf(kursi), true),
            Triple(" Ayat al-Kursi\n", listOf(kursi), true),
            Triple("Ayat al-Kursi", listOf(kursi, kursi), true),
            Triple("Ayat  al-Kursi", listOf(kursi), false),
            Triple("Ayat al-Kursi", listOf(kursi, NoteRange(3, 18, 3, 18)), false),
            Triple("Ayat al-Kursi!", listOf(kursi), false)
        )

        cases.forEach { (body, ranges, same) ->
            val database = createTestDatabase()
            database.synced.transaction { NoteStore(database.synced).insert(body, ranges, 100) }
            val sync = NotesType(database.synced)
            val syncLinks = sync.linkKey(NotesType.noteState(body, ranges, 100, 100)).identity ==
                sync.linkKey(NotesType.noteState(imported.body, listOf(kursi), 200, 200)).identity

            val result = PersistenceImportRepositoryImpl(database.quran, database.synced)
                .importData(PersistenceImportData(notes = listOf(imported)))

            assertEquals(same, syncLinks, "sync links \"$body\" on $ranges")
            assertEquals(same, result.matched == 1, "import skips for \"$body\" on $ranges")
        }
    }

    @Test
    fun `imported notes get distinct creation times`() = runTest {
        repository.importData(
            PersistenceImportData(
                notes = listOf(
                    ImportNote("First note", 2, 1, 2, 1, at(500), at(100)),
                    ImportNote("Second note", 2, 2, 2, 2, at(500), at(100))
                )
            )
        )

        assertEquals(listOf(100L, 101L), database.synced.notesQueries.all().executeAsList().map { it.created_at })
    }

    @Test
    fun `imported note bodies are trimmed`() = runTest {
        repository.importData(PersistenceImportData(notes = listOf(ImportNote("  Reflect on it \n", 2, 1, 2, 1, at(500)))))

        assertEquals("Reflect on it", database.synced.notesQueries.all().executeAsOne().body)
    }

    @Test
    fun `imported collection names are trimmed`() = runTest {
        repository.importData(PersistenceImportData(collections = listOf(ImportCollection("  Study ", at(500)))))

        assertEquals("Study", database.synced.collectionsQueries.collectionByKey("study").executeAsOne().name)
    }

    @Test
    fun `first saved membership on a highlight-only verse dates the bookmark`() = runTest {
        repository.importData(PersistenceImportData(highlights = listOf(ImportAyahHighlight(2, 255, AyahHighlightColor.BLUE, at(100)))))

        repository.importData(
            PersistenceImportData(
                collections = listOf(ImportCollection("Study", at(250)), ImportCollection("Review", at(300))),
                collectionBookmarks = listOf(
                    ImportCollectionAyahBookmark("Study", 2, 255, at(250)),
                    ImportCollectionAyahBookmark("Review", 2, 255, at(300))
                )
            )
        )

        val bookmark = collectionBookmarks.getBookmarksForCollection(collectionId("Review")).single()
        assertEquals(100L, bookmark.bookmarkAddedDate.fromPlatform().toEpochMilliseconds())
        assertEquals(250L, bookmark.bookmarkLastUpdated.fromPlatform().toEpochMilliseconds())
    }

    @Test
    fun `later saved memberships leave an already saved bookmark unchanged`() = runTest {
        repository.importData(
            PersistenceImportData(
                collections = listOf(ImportCollection("Study", at(100))),
                collectionBookmarks = listOf(ImportCollectionAyahBookmark("Study", 2, 255, at(100)))
            )
        )
        val saved = collectionBookmarks.getBookmarksForCollection(collectionId("Study")).single()

        repository.importData(
            PersistenceImportData(
                collections = listOf(ImportCollection("Review", at(400))),
                collectionBookmarks = listOf(ImportCollectionAyahBookmark("Review", 2, 255, at(400)))
            )
        )

        assertEquals(saved, collectionBookmarks.getBookmarksForCollection(collectionId("Study")).single())
    }

    @Test
    fun `membership naming a collection missing from the batch is rejected`() = runTest {
        listOf("Review", " Study").forEach { missingName ->
            assertFailsWith<IllegalArgumentException> {
                repository.importData(
                    PersistenceImportData(
                        collections = listOf(ImportCollection("Study", at(100))),
                        collectionBookmarks = listOf(ImportCollectionAyahBookmark(missingName, 2, 255, at(100)))
                    )
                )
            }
        }

        assertNull(database.synced.collectionsQueries.collectionByKey("study").executeAsOneOrNull())
        assertEquals(emptyList(), verseMemberships(2, 255))
    }

    @Test
    fun `collections sharing a canonical name form one destination`() = runTest {
        val result = repository.importData(
            PersistenceImportData(
                collections = listOf(
                    ImportCollection(" favorites ", at(100)),
                    ImportCollection("FAVORITES", at(200)),
                    ImportCollection("Study", at(100), at(40)),
                    ImportCollection("Study", at(200), at(30))
                ),
                collectionBookmarks = listOf(ImportCollectionAyahBookmark("Study", 2, 255, at(300)))
            )
        )

        val study = database.synced.collectionsQueries.collectionByKey("study").executeAsOne()
        assertEquals(1, result.collectionsImported)
        assertEquals(1, result.collectionBookmarksImported)
        assertEquals(1, result.matched)
        assertEquals(30L, study.created_at)
        assertEquals(200L, study.updated_at)
    }

    @Test
    fun `reserved system collection names are rejected`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            repository.importData(PersistenceImportData(collections = listOf(ImportCollection("system:highlights:blue", at(100)))))
        }

        assertEquals(1, database.synced.collectionsQueries.collectionsAll().executeAsList().size)
    }

    private fun insertNote(body: String, ranges: List<NoteRange>, createdAt: Long): Long =
        database.synced.transactionWithResult { NoteStore(database.synced).insert(body, ranges, createdAt) }

    private fun collectionId(name: String) =
        database.synced.collectionsQueries.collectionByKey(name.lowercase()).executeAsOne().local_id.toString()

    private fun verseMemberships(sura: Long, ayah: Long) =
        database.synced.collectionsQueries.collectionAyahsAt(sura, ayah).executeAsList()
}

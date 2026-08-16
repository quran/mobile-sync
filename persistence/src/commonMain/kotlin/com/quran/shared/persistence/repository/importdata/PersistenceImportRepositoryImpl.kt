package com.quran.shared.persistence.repository.importdata

import com.quran.shared.di.AppScope
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.ImportAyahBookmark
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.PersistenceImportResult
import com.quran.shared.persistence.model.DatabaseNote
import com.quran.shared.persistence.model.isSystemCollectionName
import com.quran.shared.persistence.repository.bookmark.BookmarkDependencyReconciler
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.fromPlatform
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
class PersistenceImportRepositoryImpl(
    private val database: QuranDatabase,
    private val reconciler: BookmarkDependencyReconciler = BookmarkDependencyReconciler(database)
) : PersistenceImportRepository {

    override suspend fun importData(
        data: PersistenceImportData,
        deleteExisting: Boolean
    ): PersistenceImportResult {
        return withContext(Dispatchers.IO) {
            validate(data)
            var result: PersistenceImportResult? = null
            database.transaction {
                if (deleteExisting) {
                    deleteExistingData()
                }
                result = mergeData(data)
            }
            requireNotNull(result)
        }
    }

    private fun mergeData(data: PersistenceImportData): PersistenceImportResult {
        val bookmarkLocalIds = importBookmarks(data.bookmarks)
        val collectionLocalIds = importCollections(data.collections)
        importReadingSessions(data.readingSessions)
        importReadingBookmark(data.readingBookmark)
        importNotes(data.notes)
        importCollectionBookmarks(
            links = data.collectionBookmarks,
            bookmarkLocalIds = bookmarkLocalIds,
            collectionLocalIds = collectionLocalIds
        )

        return PersistenceImportResult(
            bookmarksImported = data.bookmarks.size,
            collectionsImported = data.collections.size,
            collectionBookmarksImported = data.collectionBookmarks.size,
            readingSessionsImported = data.readingSessions.size,
            readingBookmarkImported = data.readingBookmark != null,
            notesImported = data.notes.size
        )
    }

    private fun deleteExistingData() {
        val timestamp = currentImportTimestampMillis()
        database.bookmark_collectionsQueries.markUnsyncedBookmarkCollectionsDeletedForImport(modified_at = timestamp)
        database.bookmark_collectionsQueries.markRemoteBookmarkCollectionsDeleted(modified_at = timestamp)
        database.bookmarksQueries.markUnsyncedBookmarksDeletedForImport(modified_at = timestamp)
        database.bookmarksQueries.markRemoteBookmarksDeleted(modified_at = timestamp)
        database.collectionsQueries.markUnsyncedCollectionsDeletedForImport(modified_at = timestamp)
        database.collectionsQueries.markRemoteCollectionsDeleted(modified_at = timestamp)
        database.notesQueries.markUnsyncedNotesDeletedForImport(modified_at = timestamp)
        database.notesQueries.markRemoteNotesDeleted(modified_at = timestamp)
        database.reading_sessionsQueries.markUnsyncedReadingSessionsDeletedForImport(modified_at = timestamp)
        database.reading_sessionsQueries.markRemoteReadingSessionsDeleted(modified_at = timestamp)
    }

    private fun validate(data: PersistenceImportData) {
        requireUniqueNonBlank(
            label = "bookmark importId",
            values = data.bookmarks.map { it.importId }
        )
        requireUniqueNonBlank(
            label = "collection importId",
            values = data.collections.map { it.importId }
        )
        requireUnique(
            label = "collection name",
            values = data.collections.map { it.name }
        )
        data.collections.forEach { collection ->
            require(collection.name.isNotBlank()) { "Collection name cannot be blank." }
            require(!isSystemCollectionName(collection.name)) {
                "System collection name is reserved: ${collection.name}."
            }
        }

        val bookmarkCoordinates = data.bookmarks.map { bookmark -> bookmark.sura to bookmark.ayah }
        requireUnique("bookmark ayah", bookmarkCoordinates)

        val readingSessionCoordinates = data.readingSessions.map { session -> session.sura to session.ayah }
        requireUnique("reading session ayah", readingSessionCoordinates)

        when (val readingBookmark = data.readingBookmark) {
            is ImportReadingBookmark.Ayah -> Unit
            is ImportReadingBookmark.Page ->
                requirePage(readingBookmark.page, "reading bookmark")
            null -> Unit
        }

        data.notes.forEach { note ->
            require(note.body.isNotBlank()) { "Note body cannot be blank." }
        }

        val bookmarkIds = data.bookmarks.map { it.importId }.toSet()
        val collectionIds = data.collections.map { it.importId }.toSet()
        val linkPairs = data.collectionBookmarks.map { link ->
            require(link.bookmarkImportId.isNotBlank()) { "Collection bookmark bookmarkImportId cannot be blank." }
            require(link.collectionImportId.isNotBlank()) { "Collection bookmark collectionImportId cannot be blank." }
            require(link.bookmarkImportId in bookmarkIds) {
                "Collection bookmark references unknown bookmark importId=${link.bookmarkImportId}."
            }
            require(link.collectionImportId in collectionIds) {
                "Collection bookmark references unknown collection importId=${link.collectionImportId}."
            }
            link.collectionImportId to link.bookmarkImportId
        }
        requireUnique("collection bookmark link", linkPairs)
    }

    private fun importBookmarks(bookmarks: List<ImportAyahBookmark>): Map<String, String> {
        if (bookmarks.isEmpty()) {
            return emptyMap()
        }
        val defaultCollection = requireNotNull(
            database.collectionsQueries.getDefaultCollection().executeAsOneOrNull()
        ) { "Default collection is not available for bookmark import." }
        return bookmarks.associate { bookmark ->
            val timestamp = bookmark.lastUpdated.toImportTimestampMillis()
            database.bookmarksQueries.upsertAyahBookmark(
                remote_id = null,
                sura = bookmark.sura.toLong(),
                ayah = bookmark.ayah.toLong(),
                created_at = timestamp,
                modified_at = timestamp
            )
            val record = database.bookmarksQueries
                .getBookmarkForAyah(bookmark.sura.toLong(), bookmark.ayah.toLong())
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected imported bookmark ${bookmark.importId}." }
            database.bookmark_collectionsQueries.addBookmarkToCollection(
                bookmark_local_id = record.local_id,
                collection_local_id = defaultCollection.local_id,
                timestamp = timestamp
            )
            bookmark.importId to record.local_id.toString()
        }
    }

    private fun importCollections(collections: List<ImportCollection>): Map<String, Long> {
        return collections.associate { collection ->
            val timestamp = collection.lastUpdated.toImportTimestampMillis()
            database.collectionsQueries.insertImportedCollection(
                name = collection.name,
                created_at = timestamp,
                modified_at = timestamp
            )
            val record = database.collectionsQueries
                .getCollectionByName(collection.name)
                .executeAsOneOrNull()
            requireNotNull(record) { "Expected imported collection ${collection.importId}." }
            require(record.is_system == 0L) {
                "System collection name is reserved: ${collection.name}."
            }
            collection.importId to record.local_id
        }
    }

    private fun importReadingSessions(readingSessions: List<ImportReadingSession>) {
        readingSessions.forEach { session ->
            val timestamp = session.lastUpdated.toImportTimestampMillis()
            database.reading_sessionsQueries.insertImportedReadingSession(
                chapter_number = session.sura.toLong(),
                verse_number = session.ayah.toLong(),
                created_at = timestamp,
                modified_at = timestamp
            )
        }
    }

    private fun importReadingBookmark(readingBookmark: ImportReadingBookmark?) {
        when (readingBookmark) {
            is ImportReadingBookmark.Ayah -> {
                val timestamp = readingBookmark.lastUpdated.toImportTimestampMillis()
                database.bookmarksQueries.setAyahReadingBookmark(
                    sura = readingBookmark.sura.toLong(),
                    ayah = readingBookmark.ayah.toLong(),
                    timestamp = timestamp
                )
                val row = requireNotNull(
                    database.bookmarksQueries
                        .getBookmarkForAyah(readingBookmark.sura.toLong(), readingBookmark.ayah.toLong())
                        .executeAsOneOrNull()
                ) { "Expected imported reading bookmark for ${readingBookmark.sura}:${readingBookmark.ayah}." }
                database.bookmarksQueries.clearOtherReadingBookmarks(
                    local_id = row.local_id,
                    timestamp = timestamp
                )
            }
            is ImportReadingBookmark.Page -> {
                val timestamp = readingBookmark.lastUpdated.toImportTimestampMillis()
                database.bookmarksQueries.setPageReadingBookmark(
                    page = readingBookmark.page.toLong(),
                    timestamp = timestamp
                )
                val row = requireNotNull(
                    database.bookmarksQueries
                        .getBookmarkForPage(readingBookmark.page.toLong())
                        .executeAsOneOrNull()
                ) { "Expected imported page reading bookmark for page=${readingBookmark.page}." }
                database.bookmarksQueries.clearOtherReadingBookmarks(
                    local_id = row.local_id,
                    timestamp = timestamp
                )
            }
            null -> Unit
        }
    }

    private fun importNotes(notes: List<ImportNote>) {
        val noteKeys = database.notesQueries.getNotes()
            .executeAsList()
            .mapTo(mutableSetOf()) { it.importKey() }

        notes.forEach { note ->
            val timestamp = note.lastUpdated.toImportTimestampMillis()
            if (!noteKeys.add(note.importKey())) {
                return@forEach
            }
            database.notesQueries.insertImportedNote(
                note = note.body,
                start_sura = note.startSura.toLong(),
                start_ayah = note.startAyah.toLong(),
                end_sura = note.endSura.toLong(),
                end_ayah = note.endAyah.toLong(),
                created_at = timestamp,
                modified_at = timestamp
            )
        }
    }

    private fun importCollectionBookmarks(
        links: List<ImportCollectionAyahBookmark>,
        bookmarkLocalIds: Map<String, String>,
        collectionLocalIds: Map<String, Long>
    ) {
        links.forEach { link ->
            val bookmarkLocalId = requireNotNull(bookmarkLocalIds[link.bookmarkImportId]) {
                "Missing local bookmark for importId=${link.bookmarkImportId}."
            }
            val collectionLocalId = requireNotNull(collectionLocalIds[link.collectionImportId]) {
                "Missing local collection for importId=${link.collectionImportId}."
            }
            val timestamp = link.lastUpdated.toImportTimestampMillis()
            database.bookmark_collectionsQueries.insertImportedBookmarkCollection(
                bookmark_local_id = bookmarkLocalId.toLong(),
                collection_local_id = collectionLocalId,
                created_at = timestamp,
                modified_at = timestamp
            )
        }
        reconciler.reconcile()
    }

    private fun requirePage(page: Int, label: String) {
        require(page in 1..MUSHAF_PAGE_COUNT) { "Invalid page for $label: $page." }
    }

    private fun PlatformDateTime.toImportTimestampMillis(): Long {
        return fromPlatform().toEpochMilliseconds()
    }

    private fun currentImportTimestampMillis(): Long {
        return kotlin.time.Clock.System.now().toEpochMilliseconds()
    }

    private fun ImportNote.importKey(): NoteImportKey {
        return NoteImportKey(
            normalizedBody = body.toNormalizedNoteText(),
            startSura = startSura.toLong(),
            startAyah = startAyah.toLong(),
            endSura = endSura.toLong(),
            endAyah = endAyah.toLong()
        )
    }

    private fun DatabaseNote.importKey(): NoteImportKey {
        return NoteImportKey(
            normalizedBody = note.toNormalizedNoteText(),
            startSura = start_sura,
            startAyah = start_ayah,
            endSura = end_sura,
            endAyah = end_ayah
        )
    }

    private fun String.toNormalizedNoteText(): String {
        return trim().replace(NOTE_WHITESPACE_REGEX, " ")
    }

    private fun <T> requireUnique(label: String, values: List<T>) {
        val duplicates = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate $label values: ${duplicates.joinToString()}." }
    }

    private fun requireUniqueNonBlank(label: String, values: List<String>) {
        values.forEach { value ->
            require(value.isNotBlank()) { "$label cannot be blank." }
        }
        requireUnique(label, values)
    }
}

private const val MUSHAF_PAGE_COUNT = 604
private val NOTE_WHITESPACE_REGEX = Regex("\\s+")

private data class NoteImportKey(
    val normalizedBody: String,
    val startSura: Long,
    val startAyah: Long,
    val endSura: Long,
    val endAyah: Long
)

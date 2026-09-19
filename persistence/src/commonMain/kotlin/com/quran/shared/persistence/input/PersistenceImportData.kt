package com.quran.shared.persistence.input

import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.util.PlatformDateTime

data class PersistenceImportData(
    val bookmarks: List<ImportAyahBookmark> = emptyList(),
    val collections: List<ImportCollection> = emptyList(),
    val collectionBookmarks: List<ImportCollectionAyahBookmark> = emptyList(),
    val readingSessions: List<ImportReadingSession> = emptyList(),
    val notes: List<ImportNote> = emptyList(),
    val readingBookmarks: List<ImportReadingBookmark> = emptyList()
)

data class ImportAyahBookmark(
    val importId: String,
    val sura: Int,
    val ayah: Int,
    val lastUpdated: PlatformDateTime
)

data class ImportCollection(
    val importId: String,
    val name: String,
    val lastUpdated: PlatformDateTime
)

data class ImportCollectionAyahBookmark(
    val collectionImportId: String,
    val bookmarkImportId: String,
    val lastUpdated: PlatformDateTime
)

data class ImportReadingSession(
    val sura: Int,
    val ayah: Int,
    val lastUpdated: PlatformDateTime
)

sealed class ImportReadingBookmark {
    abstract val slot: ReadingBookmarkSlot
    abstract val name: String?
    abstract val lastUpdated: PlatformDateTime

    data class Ayah(
        val sura: Int,
        val ayah: Int,
        override val lastUpdated: PlatformDateTime,
        override val slot: ReadingBookmarkSlot,
        override val name: String? = null
    ) : ImportReadingBookmark()

    data class Page(
        val page: Int,
        override val lastUpdated: PlatformDateTime,
        override val slot: ReadingBookmarkSlot,
        override val name: String? = null
    ) : ImportReadingBookmark()
}

data class ImportNote(
    val body: String,
    val startSura: Int,
    val startAyah: Int,
    val endSura: Int,
    val endAyah: Int,
    val lastUpdated: PlatformDateTime
)

data class PersistenceImportResult(
    val bookmarksImported: Int,
    val collectionsImported: Int,
    val collectionBookmarksImported: Int,
    val readingSessionsImported: Int,
    val notesImported: Int,
    val readingBookmarksImported: Int = 0
)

package com.quran.shared.persistence.input

import com.quran.shared.persistence.util.PlatformDateTime

data class PersistenceImportData(
    val bookmarks: List<ImportAyahBookmark> = emptyList(),
    val collections: List<ImportCollection> = emptyList(),
    val collectionBookmarks: List<ImportCollectionAyahBookmark> = emptyList(),
    val readingSessions: List<ImportReadingSession> = emptyList(),
    val readingBookmark: ImportReadingBookmark? = null,
    val notes: List<ImportNote> = emptyList()
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
    abstract val lastUpdated: PlatformDateTime

    data class Ayah(
        val sura: Int,
        val ayah: Int,
        override val lastUpdated: PlatformDateTime
    ) : ImportReadingBookmark()

    data class Page(
        val page: Int,
        override val lastUpdated: PlatformDateTime
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
    val readingBookmarkImported: Boolean,
    val notesImported: Int
)

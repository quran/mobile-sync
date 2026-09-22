package com.quran.shared.persistence.input

import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.util.PlatformDateTime

data class PersistenceImportData(
    val collections: List<ImportCollection> = emptyList(),
    val collectionBookmarks: List<ImportCollectionAyahBookmark> = emptyList(),
    val readingSessions: List<ImportReadingSession> = emptyList(),
    val notes: List<ImportNote> = emptyList(),
    val highlights: List<ImportAyahHighlight> = emptyList(),
    val readingBookmarks: List<ImportReadingBookmark> = emptyList()
)

data class ImportCollection(
    val importId: String,
    val name: String,
    val lastUpdated: PlatformDateTime,
    val createdAt: PlatformDateTime? = null
)

data class ImportCollectionAyahBookmark(
    val collectionImportId: String,
    val sura: Int,
    val ayah: Int,
    val lastUpdated: PlatformDateTime,
    val createdAt: PlatformDateTime? = null
)

data class ImportReadingSession(
    val sura: Int,
    val ayah: Int,
    val lastUpdated: PlatformDateTime,
    val createdAt: PlatformDateTime? = null
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
    val lastUpdated: PlatformDateTime,
    val createdAt: PlatformDateTime? = null
)

data class ImportAyahHighlight(
    val sura: Int,
    val ayah: Int,
    val color: AyahHighlightColor,
    val lastUpdated: PlatformDateTime,
    val createdAt: PlatformDateTime? = null
)

data class PersistenceImportResult(
    val bookmarksImported: Int = 0,
    val collectionsImported: Int = 0,
    val collectionBookmarksImported: Int = 0,
    val readingSessionsImported: Int = 0,
    val notesImported: Int = 0,
    val readingBookmarksImported: Int = 0,
    val highlightsImported: Int = 0,
    val readingSessionsUpdated: Int = 0,
    /** Components already represented by equivalent active data in the current database. */
    val matched: Int = 0,
    /** Components ignored to preserve conflicting current data or another winning import candidate. */
    val keptExisting: Int = 0,
    /** Components skipped because their fingerprints were recorded by an earlier tracked import. */
    val alreadyProcessed: Int = 0,
    /** Whether this call changed sync-managed user data; history-only writes do not count. */
    val changed: Boolean = false
)

package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.input.ImportAyahHighlight
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.pipeline.integration.SyncDevice
import com.quran.shared.pipeline.integration.UserDataSnapshot

/** Highlight color collection names, as the server stores them. */
internal const val HIGHLIGHTS_BLUE = "system:highlights:blue"
internal const val HIGHLIGHTS_GREEN = "system:highlights:green"
internal const val HIGHLIGHTS_PINK = "system:highlights:pink"

/** A legacy import with every importable type, all with fixed timestamps. */
internal val IMPORTED = PersistenceImportData(
    collections = listOf(ImportCollection("Old Page Bookmarks", at(100), at(100))),
    collectionBookmarks = listOf(
        ImportCollectionAyahBookmark("Old Page Bookmarks", 2, 255, at(110), at(110)),
        ImportCollectionAyahBookmark("Old Page Bookmarks", 2, 256, at(111), at(111))
    ),
    notes = listOf(
        ImportNote("An imported reflection on mercy", 1, 1, 1, 7, at(120), at(120)),
        ImportNote("Another imported note on patience", 2, 153, 2, 153, at(121), at(121))
    ),
    highlights = listOf(ImportAyahHighlight(3, 18, AyahHighlightColor.BLUE, at(130), at(130))),
    readingSessions = listOf(ImportReadingSession(18, 1, at(140), at(140)), ImportReadingSession(36, 1, at(150), at(150))),
    readingBookmarks = listOf(ImportReadingBookmark.Ayah(67, 1, at(160), ReadingBookmarkSlot.TEAL))
)

/** What this import should look like once synced, in [UserDataSnapshot]'s normalized form. */
internal fun PersistenceImportData.asSnapshot(): UserDataSnapshot = UserDataSnapshot(
    notes = notes.map { "${it.body} | ${it.startSura}:${it.startAyah}-${it.endSura}:${it.endAyah}" }.sorted(),
    favorites = emptyList(),
    collections = collections.map { collection ->
        val verses = collectionBookmarks.filter { it.collectionName == collection.name }.map { "${it.sura}:${it.ayah}" }
        "${collection.name}: ${verses.sorted().joinToString(",")}"
    }.sorted(),
    highlights = highlights.map { "${it.sura}:${it.ayah} ${it.color.name.lowercase()}" }.sorted(),
    sessions = readingSessions.map { "${it.sura}:${it.ayah}" }.sorted(),
    readingBookmarks = readingBookmarks.map { bookmark ->
        val location = when (bookmark) {
            is ImportReadingBookmark.Ayah -> "ayah:${bookmark.sura}:${bookmark.ayah}"
            is ImportReadingBookmark.Page -> "page:${bookmark.page}"
        }
        "${bookmark.slot.ordinal + 1} $location ${bookmark.name}"
    }.sorted()
)

/** One of every user data type, including text the server escapes. */
internal suspend fun SyncDevice.writeOneOfEach() {
    data.addNote("Q&A on <patience>", 2, 153, 2, 153)
    data.addAyahBookmarkToCollection(data.addCollection("Study").id, 3, 18)
    addToFavorites(1, 1)
    data.setHighlight(2, 255, AyahHighlightColor.BLUE)
    data.addReadingSession(18, 1)
    data.setAyahReadingBookmark(ReadingBookmarkSlot.TEAL, 18, 10, Mushaf.QCF_V2)
}

package com.quran.shared.pipeline.integration

import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.pipeline.QuranDataService
import com.quran.shared.pipeline.integration.server.CollectionRules
import com.quran.shared.pipeline.integration.server.ServerState
import kotlinx.coroutines.flow.first

/**
 * User data as the app shows it, normalized so devices and the server can be compared. Every
 * field is a sorted list, a multiset, so a duplicate on either side shows as a difference.
 * Verses are "sura:ayah"; note ranges are "sura:ayah-sura:ayah" joined by ",".
 */
data class UserDataSnapshot(
    /** "body | ranges" per note. */
    val notes: List<String>,
    val favorites: List<String>,
    /** "name: verses" per user collection, excluding Favorites and highlight collections. */
    val collections: List<String>,
    /** "verse color" per highlight. The app shows one color per verse; the server can hold several. */
    val highlights: List<String>,
    val sessions: List<String>,
    /**
     * "slot location name" per reading bookmark; a slot that is unplaced and unnamed is left out. Devices are read in
     * [Mushaf.QCF_V2], so only a `pages_604` page compares with the server's.
     */
    val readingBookmarks: List<String>
) {
    companion object {
        suspend fun of(service: QuranDataService): UserDataSnapshot {
            val collections = service.collectionsWithBookmarks.first()
            return UserDataSnapshot(
                notes = service.notes.first().map { note ->
                    note(note.body, note.ranges.joinToString(",") { "${it.startSura}:${it.startAyah}-${it.endSura}:${it.endAyah}" })
                }.sorted(),
                favorites = collections.filter { it.collection.isDefault }
                    .flatMap { c -> c.bookmarks.map { "${it.sura}:${it.ayah}" } }.sorted(),
                collections = collections.filter { !it.collection.isDefault }
                    .map { c -> collection(c.collection.name, c.bookmarks.map { "${it.sura}:${it.ayah}" }) }.sorted(),
                highlights = service.highlights.first().map { "${it.sura}:${it.ayah} ${it.color.name.lowercase()}" }.sorted(),
                sessions = service.readingSessions.first().map { "${it.sura}:${it.ayah}" }.sorted(),
                readingBookmarks = service.readingBookmarks(Mushaf.QCF_V2).first().mapNotNull { bookmark ->
                    val location = when (bookmark) {
                        is AyahReadingBookmark -> "ayah:${bookmark.sura}:${bookmark.ayah}"
                        is PageReadingBookmark -> "page:${bookmark.page}"
                        else -> null
                    }
                    readingBookmark(slotNumber(bookmark.slot.name), location, bookmark.name)
                }.sorted()
            )
        }

        /** The server's live data, with text unescaped the way the client stores it. */
        fun of(state: ServerState): UserDataSnapshot {
            val ayahs = state.bookmarks.filter { it.type == "ayah" }.associateBy { it.id }
            fun versesOf(collectionId: String) = state.memberships.filter { it.collectionId == collectionId }
                .mapNotNull { ayahs[it.bookmarkId] }.map { "${it.key}:${it.verse}" }
            val (highlightCollections, userCollections) = state.collections.partition { CollectionRules.isSystemName(it.name) }
            return UserDataSnapshot(
                notes = state.notes.map { note(unescape(it.body), it.ranges.joinToString(",")) }.sorted(),
                favorites = state.bookmarks.filter { it.inDefault && it.type == "ayah" }.map { "${it.key}:${it.verse}" }.sorted(),
                collections = userCollections.map { collection(unescape(it.name), versesOf(it.id)) }.sorted(),
                highlights = highlightCollections
                    .flatMap { c -> versesOf(c.id).map { "$it ${c.name.substringAfterLast(':').lowercase()}" } }.sorted(),
                sessions = state.sessions.map { "${it.chapter}:${it.verse}" }.sorted(),
                readingBookmarks = state.readingBookmarks.mapNotNull { bookmark ->
                    val location = when (bookmark.type) {
                        "ayah" -> "ayah:${bookmark.key}:${bookmark.verse}"
                        "page" -> if (bookmark.group == "pages_604") "page:${bookmark.key}" else "page:${bookmark.group}:${bookmark.key}"
                        else -> null
                    }
                    readingBookmark(bookmark.slot, location, bookmark.name)
                }.sorted()
            )
        }

        private fun note(body: String, ranges: String) = "$body | $ranges"

        private fun collection(name: String, verses: List<String>) = "$name: ${verses.sorted().joinToString(",")}"

        private fun readingBookmark(slot: Int, location: String?, name: String?): String? =
            if (location == null && name == null) null else "$slot $location $name"

        private fun slotNumber(name: String): Int = when (name) {
            "TEAL" -> 1
            "ORANGE" -> 2
            "RED" -> 3
            else -> error("Unknown reading bookmark slot $name")
        }

        /** The client's inverse of the server's `&`, `<` and `>` escaping. */
        private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}

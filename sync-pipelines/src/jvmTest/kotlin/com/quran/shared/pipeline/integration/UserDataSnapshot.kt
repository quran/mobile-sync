package com.quran.shared.pipeline.integration

import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.pipeline.QuranDataService
import com.quran.shared.pipeline.integration.server.CollectionRules
import com.quran.shared.pipeline.integration.server.ServerState
import kotlinx.coroutines.flow.first

/**
 * User data as the app shows it, normalized so devices and the server can be compared as sets.
 * Verses are "sura:ayah"; note ranges are "sura:ayah-sura:ayah" joined by ",".
 */
data class UserDataSnapshot(
    val notes: Set<Pair<String, String>>,
    val favorites: Set<String>,
    /** User collections by name, excluding Favorites and highlight collections. */
    val collections: Map<String, Set<String>>,
    /** Verse to highlight colors. The app shows one color per verse; the server can hold several. */
    val highlights: Map<String, Set<String>>,
    val sessions: Set<String>,
    /** Slot (1..3) to (location, name); a never-used slot is left out. */
    val readingBookmarks: Map<Int, Pair<String?, String?>>
) {
    companion object {
        suspend fun of(service: QuranDataService): UserDataSnapshot {
            val collections = service.collectionsWithBookmarks.first()
            return UserDataSnapshot(
                notes = service.notes.first().map {
                    it.body to "${it.startSura}:${it.startAyah}-${it.endSura}:${it.endAyah}"
                }.toSet(),
                favorites = collections.filter { it.collection.isDefault }
                    .flatMap { c -> c.bookmarks.map { "${it.sura}:${it.ayah}" } }.toSet(),
                collections = collections.filter { !it.collection.isDefault }
                    .associate { c -> c.collection.name to c.bookmarks.map { "${it.sura}:${it.ayah}" }.toSet() },
                highlights = service.highlights.first()
                    .groupBy({ "${it.sura}:${it.ayah}" }, { it.color.name.lowercase() })
                    .mapValues { it.value.toSet() },
                sessions = service.readingSessions.first().map { "${it.sura}:${it.ayah}" }.toSet(),
                readingBookmarks = service.readingBookmarks.first().associate { bookmark ->
                    val location = when (bookmark) {
                        is AyahReadingBookmark -> "ayah:${bookmark.sura}:${bookmark.ayah}"
                        is PageReadingBookmark -> "page:${bookmark.page}"
                        else -> null
                    }
                    slotNumber(bookmark.slot.name) to (location to bookmark.name)
                }.filterValues { it != null to null }
            )
        }

        /** The server's live data, with text unescaped the way the client stores it. */
        fun of(state: ServerState): UserDataSnapshot {
            val ayahs = state.bookmarks.filter { it.type == "ayah" }.associateBy { it.id }
            fun versesOf(collectionId: String) = state.memberships.filter { it.collectionId == collectionId }
                .mapNotNull { ayahs[it.bookmarkId] }.map { "${it.key}:${it.verse}" }.toSet()
            val (highlightCollections, userCollections) = state.collections.partition { CollectionRules.isSystemName(it.name) }
            return UserDataSnapshot(
                notes = state.notes.map { unescape(it.body) to it.ranges.joinToString(",") }.toSet(),
                favorites = state.favorites,
                collections = userCollections.associate { unescape(it.name) to versesOf(it.id) },
                highlights = highlightCollections
                    .flatMap { c -> versesOf(c.id).map { it to c.name.substringAfterLast(':').lowercase() } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { it.value.toSet() },
                sessions = state.sessions.map { "${it.chapter}:${it.verse}" }.toSet(),
                readingBookmarks = state.readingBookmarks.associate { bookmark ->
                    val location = when (bookmark.type) {
                        "ayah" -> "ayah:${bookmark.key}:${bookmark.verse}"
                        "page" -> "page:${bookmark.key}"
                        else -> null
                    }
                    bookmark.slot to (location to bookmark.name)
                }.filterValues { it != null to null }
            )
        }

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

package com.quran.shared.syncv2.types

/**
 * The groups the server files page bookmarks under, picked by the written mushaf (`getPageBookmarkGroupByMushafId`).
 * [standInMushafId] picks the same group again, so a stored location round-trips unchanged.
 */
enum class PageGroup(val serverName: String, val pageCount: Int, val standInMushafId: Int) {
    /** The Madani layouts, 604 pages. */
    PAGES_604("pages_604", 604, 5),
    /** The 15-line IndoPak layout, 610 pages. */
    PAGES_610("pages_610", 610, 6),
    /** The 16-line IndoPak layout, 548 pages. */
    PAGES_548("pages_548", 548, 7);

    companion object {
        /** The group the server stores a page written with [mushafId] under. */
        fun ofMushaf(mushafId: Int): PageGroup = when (mushafId) {
            6 -> PAGES_610
            7 -> PAGES_548
            else -> PAGES_604
        }

        /** The group named [serverName], or null for a group this library doesn't know. */
        fun named(serverName: String): PageGroup? = entries.firstOrNull { it.serverName == serverName }
    }
}

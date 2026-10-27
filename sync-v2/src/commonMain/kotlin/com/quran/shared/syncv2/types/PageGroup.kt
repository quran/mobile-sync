package com.quran.shared.syncv2.types

/**
 * The groups the server files page bookmarks under, picked by the written mushaf ([ServerMushaf.pageGroup]).
 * [standIn] picks the same group again, so a stored location round-trips unchanged.
 */
enum class PageGroup(val serverName: String, val pageCount: Int) {
    /** The Madani layouts, 604 pages. */
    PAGES_604("pages_604", 604),
    /** The 15-line IndoPak layout, 610 pages. */
    PAGES_610("pages_610", 610),
    /** The 16-line IndoPak layout, 548 pages. */
    PAGES_548("pages_548", 548);

    /**
     * The mushaf this library writes for the group. A getter, not a constructor value: the two enums refer to each
     * other.
     */
    val standIn: ServerMushaf
        get() = when (this) {
            PAGES_604 -> ServerMushaf.KFGQPC_HAFS
            PAGES_610 -> ServerMushaf.INDOPAK_15_LINES
            PAGES_548 -> ServerMushaf.INDOPAK_16_LINES
        }

    companion object {
        /** The group named [serverName], or null for a group this library doesn't know. */
        fun named(serverName: String): PageGroup? = entries.firstOrNull { it.serverName == serverName }
    }
}

package com.quran.shared.syncv2.types

/**
 * The server's mushafs. Names and ids follow the backend's `Mushaf` enum (quran.com-users-backend
 * `packages/types/src/preferences/Mushaf.ts`), with one of its spellings where it has aliases. Ids 20 to 26 are on the
 * upcoming backend (its `mapping-v2`/`testing` branch); backend `main` stops at 19. [pageCount] is the pages the server
 * accepts for the mushaf (`PAGES_MUSHAF_MAP`).
 *
 * Quran.com names a mushaf after the font or text it is rendered with, not its page layout. KFGQPC is the King Fahd
 * Glorious Qur'an Printing Complex (Madinah), which prints the Madani mushaf; QCF (Quran Complex Fonts) are the
 * Complex's per-page fonts that reproduce its printed pages.
 */
enum class ServerMushaf(val id: Int, val pageCount: Int) {
    /** The newer Madani print (the apps' 1440 layout), rendered with QCF V2 page fonts. */
    QCF_V2(1, 604),

    /** The 1405 AH Madani print, rendered with QCF V1 page fonts. */
    QCF_V1(2, 604),

    /**
     * IndoPak script on the Madani 604-page pagination. Unused: the Quran.com web only offers the 15- and 16-line
     * IndoPak prints. Read as [QCF_V2]'s pages (checked against the Quran.com API; the mapping bundle lacks it).
     */
    INDOPAK(3, 604),

    /** Uthmani Hafs text, paged like [QCF_V2]. */
    UTHMANI_HAFS(4, 604),

    /** The Complex's digital Uthmani Hafs text font, paged like [QCF_V2]. */
    KFGQPC_HAFS(5, 604),

    /** The 15-line IndoPak print, 610 pages. */
    INDOPAK_15_LINES(6, 610),

    /** The 16-line IndoPak print, 548 pages. */
    INDOPAK_16_LINES(7, 548),

    /** Color-coded tajweed, paged like [QCF_V1]. */
    TAJWEED(11, 604),

    /** Color-coded tajweed with QCF V4 page fonts, paged like [QCF_V2]. */
    QCF_TAJWEED_V4(19, 604),

    /** The Warsh riwayah, 604 pages. */
    WARSH(20, 604),

    /** The Doori riwayah, 604 pages. */
    DOORI(21, 604),

    /** The Qaloun riwayah, 604 pages. */
    QALOUN(22, 604),

    /** The Shu'bah riwayah, 604 pages. */
    SHUBA(23, 604),

    /** The Sousi riwayah, 604 pages. */
    SOUSI(24, 604),

    /** The Bazzi riwayah, paged like [QCF_V2] (checked against the Quran.com API: the mapping bundle lacks it). */
    BAZZI(25, 604),

    /** The Qumbul riwayah, paged like [QCF_V2] (checked against the Quran.com API: the mapping bundle lacks it). */
    QUMBUL(26, 604);

    companion object {
        /** The mushaf with [id], or null for an id the backend doesn't have. */
        fun fromId(id: Int): ServerMushaf? = entries.firstOrNull { it.id == id }
    }
}

package com.quran.shared.syncv2.types

/**
 * The server's mushafs. Names and ids follow the backend's `Mushaf` enum (quran.com-users-backend
 * `packages/types/src/preferences/Mushaf.ts`), with one of its spellings where it has aliases. Ids 20 to 26 are on the
 * upcoming backend (its `mapping-v2`/`testing` branch); backend `main` stops at 19. [pageGroup] is the group the server
 * files a page of the mushaf under.
 *
 * Quran.com names a mushaf after the font or text it is rendered with, not its page layout. KFGQPC is the King Fahd
 * Glorious Qur'an Printing Complex (Madinah), which prints the Madani mushaf; QCF (Quran Complex Fonts) are the
 * Complex's per-page fonts that reproduce its printed pages.
 */
enum class ServerMushaf(val id: Int, val pageGroup: PageGroup) {
    /** The newer Madani print (the apps' 1440 layout), rendered with QCF V2 page fonts. */
    QCF_V2(1, PageGroup.PAGES_604),

    /** The 1405 AH Madani print, rendered with QCF V1 page fonts. */
    QCF_V1(2, PageGroup.PAGES_604),

    /** IndoPak script, paged like [QCF_V2] on Quran.com (checked against its API: the mapping bundle lacks it). */
    INDOPAK(3, PageGroup.PAGES_604),

    /** Uthmani Hafs text, paged like [QCF_V2]. */
    UTHMANI_HAFS(4, PageGroup.PAGES_604),

    /** The Complex's digital Uthmani Hafs text font, paged like [QCF_V2]. */
    KFGQPC_HAFS(5, PageGroup.PAGES_604),

    /** The 15-line IndoPak print, 610 pages. */
    INDOPAK_15_LINES(6, PageGroup.PAGES_610),

    /** The 16-line IndoPak print, 548 pages. */
    INDOPAK_16_LINES(7, PageGroup.PAGES_548),

    /** Color-coded tajweed, paged like [QCF_V1]. */
    TAJWEED(11, PageGroup.PAGES_604),

    /** Color-coded tajweed with QCF V4 page fonts, paged like [QCF_V2]. */
    QCF_TAJWEED_V4(19, PageGroup.PAGES_604),

    /** The Warsh riwayah, 604 pages. */
    WARSH(20, PageGroup.PAGES_604),

    /** The Doori riwayah, 604 pages. */
    DOORI(21, PageGroup.PAGES_604),

    /** The Qaloun riwayah, 604 pages. */
    QALOUN(22, PageGroup.PAGES_604),

    /** The Shu'bah riwayah, 604 pages. */
    SHUBA(23, PageGroup.PAGES_604),

    /** The Sousi riwayah, 604 pages. */
    SOUSI(24, PageGroup.PAGES_604),

    /** The Bazzi riwayah, paged like [QCF_V2] (checked against the Quran.com API: the mapping bundle lacks it). */
    BAZZI(25, PageGroup.PAGES_604),

    /** The Qumbul riwayah, paged like [QCF_V2] (checked against the Quran.com API: the mapping bundle lacks it). */
    QUMBUL(26, PageGroup.PAGES_604);

    companion object {
        /** The mushaf with [id], or null for an id the backend doesn't have. */
        fun fromId(id: Int): ServerMushaf? = entries.firstOrNull { it.id == id }
    }
}

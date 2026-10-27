package com.quran.shared.persistence.model

import com.quran.shared.persistence.repository.readingbookmark.mapping.PageLayout
import com.quran.shared.syncv2.types.ServerMushaf

/**
 * The page layouts apps show the Quran in, and set and read page reading bookmarks in. mobile-sync maps pages between
 * the Hafs layouts; the riwayat ([WARSH], [QALOUN]) number verses differently, so their pages are never mapped.
 */
enum class Mushaf(internal val serverMushaf: ServerMushaf) {
    /** The 1405 AH Madani print, 604 pages: sent as the server's `QCF_V1` (id 2). */
    MADANI_1405(ServerMushaf.QCF_V1),

    /** The newer Madani print (1440), 604 pages: sent as the server's `QCF_V2` (id 1). */
    MADANI_1440(ServerMushaf.QCF_V2),

    /**
     * The 15-line IndoPak print: sent as the server's `INDOPAK_15_LINES` (id 6). Apps number it 2..611 (page 1 is the
     * cover); the server numbers it 1..610.
     */
    INDOPAK_15_LINES(ServerMushaf.INDOPAK_15_LINES),

    /**
     * The Shemerly print, 521 pages. The server has no Shemerly mushaf yet (quran/mobile-sync#355), so for now a page
     * is sent as the [MADANI_1440] page that holds its verses (`QCF_V2`, id 1) and read back like any other layout's.
     * This is temporary: once the server has an id, add it to [ServerMushaf] (521 pages), map it to the Shemerly
     * layout in `PageLayout.of`, and send this mushaf as it. Apps don't change.
     */
    SHEMERLY(ServerMushaf.QCF_V2),

    /**
     * The Warsh riwayah, 604 pages: stored as the server's `WARSH` (id 20). Its pages map to no other mushaf: locally a
     * page reads unchanged in this mushaf and as an empty slot in any other. Riwayah app builds don't sync: today's
     * backend rejects the id, so the change is held, and a backend that accepts it but keeps only the page group would
     * relabel it as a 1440 page until it keeps the mushaf id (quran/mobile-sync#354).
     */
    WARSH(ServerMushaf.WARSH),

    /** The Qaloun riwayah, 604 pages: stored as the server's `QALOUN` (id 22). Pages and sync as for [WARSH]. */
    QALOUN(ServerMushaf.QALOUN)
}

/** The layout this mushaf's pages are numbered in; null for the riwayat, whose pages don't map. */
internal val Mushaf.layout: PageLayout?
    get() = when (this) {
        Mushaf.MADANI_1405 -> PageLayout.MADANI_1405
        Mushaf.MADANI_1440 -> PageLayout.MADANI_1440
        Mushaf.INDOPAK_15_LINES -> PageLayout.INDOPAK_15_LINES
        Mushaf.SHEMERLY -> PageLayout.SHEMERLY
        Mushaf.WARSH, Mushaf.QALOUN -> null
    }

private val Mushaf.appPageOffset: Int get() = if (this == Mushaf.INDOPAK_15_LINES) 1 else 0

internal val Mushaf.appPages: IntRange get() = (1 + appPageOffset)..((layout?.pageCount ?: serverMushaf.pageCount) + appPageOffset)

internal fun Mushaf.toServerPage(appPage: Int): Int = appPage - appPageOffset

internal fun Mushaf.toAppPage(serverPage: Int): Int = serverPage + appPageOffset

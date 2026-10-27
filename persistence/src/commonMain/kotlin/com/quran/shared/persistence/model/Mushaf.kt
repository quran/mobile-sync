package com.quran.shared.persistence.model

import com.quran.shared.persistence.repository.readingbookmark.mapping.PageLayout
import com.quran.shared.syncv2.types.PageGroup
import com.quran.shared.syncv2.types.ServerMushaf

/**
 * The page layouts apps show the Quran in, and set and read page reading bookmarks in. mobile-sync maps pages between
 * them.
 */
enum class Mushaf(internal val serverMushaf: ServerMushaf) {
    /** The 1405 AH Madani print, 604 pages: the server's `QCF_V1` (id 2), filed under `pages_604`. */
    MADANI_1405(ServerMushaf.QCF_V1),

    /** The newer Madani print (1440), 604 pages: the server's `QCF_V2` (id 1), filed under `pages_604`. */
    MADANI_1440(ServerMushaf.QCF_V2),

    /**
     * The 15-line IndoPak print: the server's `INDOPAK_15_LINES` (id 6), filed under `pages_610`. Apps number it 2..611
     * (page 1 is the cover); the server numbers it 1..610.
     */
    INDOPAK_15_LINES(ServerMushaf.INDOPAK_15_LINES)
}

internal val Mushaf.pageGroup: PageGroup get() = serverMushaf.pageGroup

internal val Mushaf.layout: PageLayout
    get() = when (this) {
        Mushaf.MADANI_1405 -> PageLayout.MADANI_1405
        Mushaf.MADANI_1440 -> PageLayout.MADANI_1440
        Mushaf.INDOPAK_15_LINES -> PageLayout.INDOPAK_15_LINES
    }

private val Mushaf.appPageOffset: Int get() = if (this == Mushaf.INDOPAK_15_LINES) 1 else 0

internal val Mushaf.appPages: IntRange get() = (1 + appPageOffset)..(pageGroup.pageCount + appPageOffset)

internal fun Mushaf.toServerPage(appPage: Int): Int = appPage - appPageOffset

internal fun Mushaf.toAppPage(serverPage: Int): Int = serverPage + appPageOffset

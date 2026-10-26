package com.quran.shared.persistence.model

import com.quran.shared.persistence.repository.readingbookmark.mapping.PageLayout
import com.quran.shared.syncv2.types.PageGroup

/** The page layout an app shows the Quran in, and sets and reads page reading bookmarks in. */
enum class Mushaf {
    /** The 1405 Madani layout, 604 pages (server mushaf 2). */
    QCF_V1,

    /** The 1440 Madani layout, 604 pages (server mushaf 1). */
    QCF_V2,

    /**
     * The 15-line IndoPak layout (server mushaf 6). Apps skip a cover page and number its pages 2 to 611, so app page N
     * is the server's page N - 1.
     */
    INDOPAK_15_LINES
}

internal val Mushaf.serverMushafId: Int
    get() = when (this) {
        Mushaf.QCF_V1 -> 2
        Mushaf.QCF_V2 -> 1
        Mushaf.INDOPAK_15_LINES -> 6
    }

internal val Mushaf.pageGroup: PageGroup get() = PageGroup.ofMushaf(serverMushafId)

internal val Mushaf.layout: PageLayout
    get() = when (this) {
        Mushaf.QCF_V1 -> PageLayout.MADANI_1405
        Mushaf.QCF_V2 -> PageLayout.MADANI_1440
        Mushaf.INDOPAK_15_LINES -> PageLayout.INDOPAK_15_LINES
    }

private val Mushaf.appPageOffset: Int get() = if (this == Mushaf.INDOPAK_15_LINES) 1 else 0

internal val Mushaf.appPages: IntRange get() = (1 + appPageOffset)..(pageGroup.pageCount + appPageOffset)

internal fun Mushaf.toServerPage(appPage: Int): Int = appPage - appPageOffset

internal fun Mushaf.toAppPage(serverPage: Int): Int = serverPage + appPageOffset

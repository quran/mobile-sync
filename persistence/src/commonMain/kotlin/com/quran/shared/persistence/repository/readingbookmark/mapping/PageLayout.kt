package com.quran.shared.persistence.repository.readingbookmark.mapping

/**
 * A page layout: each page's verses as `[firstVerseId, lastVerseId]`, in canonical Hafs verse ids (1 to 6236, counted
 * through the surahs). Pages are numbered from 1 the way the server numbers them. Consecutive pages share at most one
 * verse, one that runs across the page break (only in the 16-line IndoPak layout).
 */
internal enum class PageLayout(private val ranges: IntArray) {
    MADANI_1440(Madani1440Pages.ranges),
    MADANI_1405(Madani1405Pages.ranges),
    INDOPAK_15_LINES(IndoPak15LinePages.ranges),
    INDOPAK_16_LINES(IndoPak16LinePages.ranges);

    val pageCount: Int get() = ranges.size / 2

    fun firstVerse(page: Int): Int = ranges[2 * (page - 1)]

    fun lastVerse(page: Int): Int = ranges[2 * (page - 1) + 1]

    fun middleVerse(page: Int): Int = (firstVerse(page) + lastVerse(page)) / 2

    /** The first page holding [verseId]: the page a verse running across a page break starts on. */
    fun pageOf(verseId: Int): Int {
        var low = 1
        var high = pageCount
        while (low < high) {
            val middle = (low + high) / 2
            if (lastVerse(middle) < verseId) low = middle + 1 else high = middle
        }
        return low
    }
}

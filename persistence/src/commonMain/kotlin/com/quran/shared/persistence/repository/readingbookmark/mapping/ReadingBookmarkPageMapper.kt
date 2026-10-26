package com.quran.shared.persistence.repository.readingbookmark.mapping

import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.appPages
import com.quran.shared.persistence.model.layout
import com.quran.shared.persistence.model.pageGroup
import com.quran.shared.persistence.model.toAppPage
import com.quran.shared.persistence.model.toServerPage
import com.quran.shared.syncv2.types.PageGroup
import com.quran.shared.syncv2.types.ReadingLocation
import kotlin.math.abs

/**
 * Page reading bookmarks between the app's mushaf and the server's page groups. A page is stored the way the server
 * expects (its group and the group's page number) and read back as the page of the app's mushaf that holds the same
 * verses. This is the rule both apps use (quran_android `ReadingBookmarkPageMapper.mapPage`).
 */
internal object ReadingBookmarkPageMapper {

    /** The location of [appPage] of [mushaf]; throws [IllegalArgumentException] for a page the mushaf doesn't have. */
    fun serverLocation(appPage: Int, mushaf: Mushaf): ReadingLocation =
        requireNotNull(serverLocationOrNull(appPage, mushaf)) { "Page $appPage is outside $mushaf's pages ${mushaf.appPages}" }

    fun serverLocationOrNull(appPage: Int, mushaf: Mushaf): ReadingLocation? =
        if (appPage in mushaf.appPages) ReadingLocation.page(mushaf.toServerPage(appPage), mushaf.pageGroup) else null

    /**
     * The page of [mushaf] showing [location]'s page, or null when its group is unknown. A page of the mushaf's own
     * group keeps its number, even between the two Madani layouts.
     */
    fun appPage(location: ReadingLocation, mushaf: Mushaf): Int? {
        val group = location.pageGroup ?: return null
        val page = if (group == mushaf.pageGroup) location.key else mapPage(location.key, group.layout, mushaf.layout)
        return mushaf.toAppPage(page)
    }

    /**
     * The [target] page for [page] of [source], both in server numbering. It prefers the target page holding the
     * source page's middle verse; among the target pages from the source page's first to last verse, it keeps those
     * whose own middle verse is on the source page (all of them when none is), and picks the one nearest the
     * preferred page, the lower on a tie.
     */
    fun mapPage(page: Int, source: PageLayout, target: PageLayout): Int {
        val sourcePage = page.coerceIn(1, source.pageCount)
        val preferred = target.pageOf(source.middleVerse(sourcePage))
        val first = target.pageOf(source.firstVerse(sourcePage))
        val last = target.pageOf(source.lastVerse(sourcePage))
        val candidates = (minOf(first, last, preferred)..maxOf(first, last, preferred)).toList()
        return candidates.filter { source.pageOf(target.middleVerse(it)) == sourcePage }
            .ifEmpty { candidates }
            .minBy { abs(it - preferred) }
    }

    /** The layout a group's pages are read in: the server's stand-in for `pages_604` is the 1440 Madani layout. */
    private val PageGroup.layout: PageLayout
        get() = when (this) {
            PageGroup.PAGES_604 -> PageLayout.MADANI_1440
            PageGroup.PAGES_610 -> PageLayout.INDOPAK_15_LINES
            PageGroup.PAGES_548 -> PageLayout.INDOPAK_16_LINES
        }
}

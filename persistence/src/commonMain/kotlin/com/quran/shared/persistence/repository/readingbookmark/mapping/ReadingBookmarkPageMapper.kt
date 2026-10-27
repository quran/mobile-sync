package com.quran.shared.persistence.repository.readingbookmark.mapping

import com.quran.shared.persistence.model.Mushaf
import com.quran.shared.persistence.model.appPages
import com.quran.shared.persistence.model.layout
import com.quran.shared.persistence.model.toAppPage
import com.quran.shared.persistence.model.toServerPage
import com.quran.shared.syncv2.types.ReadingLocation
import kotlin.math.abs

/**
 * Maps page reading bookmarks between the app's mushaf and the mushaf they were set in with the rule both apps use
 * (quran_android `ReadingBookmarkPageMapper.mapPage`), fixed as [mapPage] describes for verses that run across a page
 * break.
 */
internal object ReadingBookmarkPageMapper {

    /** The location of [appPage] of [mushaf]; throws [IllegalArgumentException] for a page the mushaf doesn't have. */
    fun serverLocation(appPage: Int, mushaf: Mushaf): ReadingLocation =
        requireNotNull(serverLocationOrNull(appPage, mushaf)) { "Page $appPage is outside $mushaf's pages ${mushaf.appPages}" }

    /**
     * The location [appPage] of [mushaf] is sent as, or null for a page the mushaf doesn't have. A mushaf sent as a
     * server mushaf of another layout ([Mushaf.SHEMERLY], until the server has its own) sends the page of that layout
     * holding its verses.
     */
    fun serverLocationOrNull(appPage: Int, mushaf: Mushaf): ReadingLocation? {
        if (appPage !in mushaf.appPages) return null
        val page = mushaf.toServerPage(appPage)
        val layout = mushaf.layout
        val serverLayout = PageLayout.of(mushaf.serverMushaf.id)
        val serverPage = if (layout == serverLayout) page else mapPage(page, checkNotNull(layout), checkNotNull(serverLayout))
        return ReadingLocation.page(serverPage, mushaf.serverMushaf)
    }

    /**
     * The page of [mushaf] showing [location]'s page, or null when its mushaf has no layout here ([PageLayout.of]) or
     * the page isn't one of its mushaf's. A page of the same layout keeps its number. The server's stand-in for
     * `pages_604` (5, KFGQPC Hafs) is read in the 1440 layout, its own. A mushaf without a layout (a riwayah) reads
     * only its own pages, unchanged.
     */
    fun appPage(location: ReadingLocation, mushaf: Mushaf): Int? {
        val target = mushaf.layout ?: return location.key.takeIf {
            location.mushafId == mushaf.serverMushaf.id && it in 1..mushaf.serverMushaf.pageCount
        }
        val source = PageLayout.of(location.mushafId) ?: return null
        if (location.key !in 1..source.pageCount) return null
        val page = if (source == target) location.key else mapPage(location.key, source, target)
        return mushaf.toAppPage(page)
    }

    /**
     * The [target] page for [page] of [source], both in server numbering; [page] must be one of [source]'s. It prefers
     * the target page holding the source page's middle verse; among the target pages from the source page's first to
     * last verse, it keeps those whose own middle verse is on the source page (all of them when none is), and picks
     * the one nearest the preferred page, the lower on a tie.
     *
     * "On the source page" is the page's verse range, not the first page holding the verse as the apps check it: in
     * the 16-line IndoPak layout a verse running across a page break is on both pages, and the apps' check would drop
     * a candidate whose middle verse is the one the source page starts with (16-line page 43 would map to 1440 page
     * 48, which holds only its last verse, instead of 47).
     */
    fun mapPage(page: Int, source: PageLayout, target: PageLayout): Int {
        require(page in 1..source.pageCount) { "Page $page is outside $source's pages" }
        val verses = source.firstVerse(page)..source.lastVerse(page)
        val preferred = target.pageOf(source.middleVerse(page))
        val first = target.pageOf(verses.first)
        val last = target.pageOf(verses.last)
        val candidates = (minOf(first, last, preferred)..maxOf(first, last, preferred)).toList()
        return candidates.filter { target.middleVerse(it) in verses }
            .ifEmpty { candidates }
            .minBy { abs(it - preferred) }
    }
}

package com.quran.shared.persistence.repository.readingbookmark.mapping

import com.quran.shared.syncv2.types.ServerMushaf
import kotlin.test.Test
import kotlin.test.assertEquals

class BundleMushafPageCountsTest {

    @Test
    fun `each server mushaf in the bundle has the bundle's page count`() {
        ServerMushaf.entries.filter { it.id in bundleMushafPageCounts }.forEach { mushaf ->
            assertEquals(bundleMushafPageCounts.getValue(mushaf.id), mushaf.pageGroup.pageCount, "$mushaf")
        }
    }

    /** These were checked against the Quran.com API instead: each has the QCF_V2 layout's pages. */
    @Test
    fun `only the known exceptions are missing from the bundle`() {
        assertEquals(
            setOf(ServerMushaf.INDOPAK, ServerMushaf.BAZZI, ServerMushaf.QUMBUL),
            ServerMushaf.entries.filter { it.id !in bundleMushafPageCounts }.toSet()
        )
    }
}

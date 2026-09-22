package com.quran.shared.persistence.repository

import com.quran.shared.persistence.input.ImportAyahHighlight
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.ReadingBookmarkSlot
import com.quran.shared.persistence.repository.importdata.ImportFingerprint
import com.quran.shared.persistence.util.toPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ImportFingerprintTest {
    @Test
    fun `fingerprint byte format remains stable`() {
        assertEquals(
            "v1:4a57353b739d7e5dfa7e672d539346b3abb3982f660d3bf5145306c16e3bbff6",
            ImportFingerprint.note(ImportNote("hello world", 2, 1, 2, 3, at(0)))
        )
        assertEquals(
            "v1:f010c9a0ec8746a2a22457447154a79b07ea7d0dcf25d318a8d67bd739e09ad6",
            ImportFingerprint.collection(ImportCollection("collection", "Old Page Bookmarks", at(0)))
        )
        assertEquals(
            "v1:e4eb390d2dcdca1ba6ff9da8c23c4fd7c64e5aea5c6ced9be63657cf3d71eab1",
            ImportFingerprint.highlight(ImportAyahHighlight(2, 255, AyahHighlightColor.YELLOW, at(0)))
        )
        assertEquals(
            "v1:5d6b9fba36ed9de80420ab1464c0361a5ca15cca96c95e2dc0b9049c3d85f5ff",
            ImportFingerprint.collectionMembership(
                ImportCollectionAyahBookmark("favorites", 2, 255, at(0)),
                ImportCollection("favorites", "Favorites", at(0))
            )
        )
        assertEquals(
            "v1:1f64caca1ada307850d3354f34e54ee83e7c19034e16092cbe75841bcf865568",
            ImportFingerprint.readingSession(ImportReadingSession(2, 255, at(123_456_789)))
        )
        assertEquals(
            "v1:956c90f53e9b0756d41c39df3ea185082056cd5ae1059f777ac21f8e75199270",
            ImportFingerprint.readingBookmark(
                ImportReadingBookmark.Ayah(
                    sura = 2,
                    ayah = 255,
                    lastUpdated = at(0),
                    slot = ReadingBookmarkSlot.GREEN,
                    name = null
                )
            )
        )
        assertEquals(
            "v1:20dc47280585a5a805f08cc30a0a098c82393c8f0d955e8afa8b1bd8cf25ea5f",
            ImportFingerprint.readingBookmark(
                ImportReadingBookmark.Ayah(
                    sura = 2,
                    ayah = 255,
                    lastUpdated = at(0),
                    slot = ReadingBookmarkSlot.GREEN,
                    name = ""
                )
            )
        )
    }

    private fun at(millis: Long) = Instant.fromEpochMilliseconds(millis).toPlatform()
}

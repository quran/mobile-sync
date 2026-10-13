package com.quran.shared.mutations

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTextEscapingTest {

    @Test
    fun `encoding escapes only ampersands and angle brackets`() {
        assertEquals("Tom &amp; Jerry", "Tom & Jerry".escapeServerText())
        assertEquals("a &lt; b", "a < b".escapeServerText())
        assertEquals("&lt;b&gt;bold&lt;/b&gt;", "<b>bold</b>".escapeServerText())
        assertEquals("&amp;amp;", "&amp;".escapeServerText())
        assertEquals("\"quoted\" it's", "\"quoted\" it's".escapeServerText())
    }

    @Test
    fun `decoding is a single pass`() {
        assertEquals("&lt;", "&amp;lt;".unescapeServerText())
        assertEquals("&amp;", "&amp;amp;".unescapeServerText())
        assertEquals("x<y & z>", "x&lt;y &amp; z&gt;".unescapeServerText())
    }

    @Test
    fun `text survives an encode and decode round trip`() {
        listOf(
            "Tom & Jerry",
            "a < b",
            "x<y",
            "<b>bold</b>",
            "&amp;",
            "&amp;lt;",
            "&lt;already escaped&gt;",
            "قل هو الله أحد — النصر والاستغفار",
            "Reflect 🤍✨",
            "first line\nsecond line\n\nfourth",
            "system:highlights:blue"
        ).forEach { text ->
            assertEquals(text, text.escapeServerText().unescapeServerText())
        }
    }
}

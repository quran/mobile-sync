package com.quran.shared.mutations

import kotlin.test.Test
import kotlin.test.assertEquals

private const val WHITESPACE = "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006" +
    "\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"

class ServerTextTest {

    @Test
    fun `trims every character JavaScript trim removes`() {
        assertEquals("Study", "${WHITESPACE}Study$WHITESPACE".trimLikeServer())
    }

    @Test
    fun `keeps characters JavaScript trim keeps`() {
        val kept = "\u001C\u001D\u001E\u001F\u200B"

        assertEquals("${kept}Study$kept", "${kept}Study$kept".trimLikeServer())
    }

    @Test
    fun `collapsing trims like the server and makes each whitespace run inside one space`() {
        assertEquals(
            "Ayat al-Kursi, verse 255",
            "$WHITESPACE Ayat\n\nal-Kursi,${WHITESPACE}verse\u00A0255 \uFEFF".collapseServerWhitespace()
        )
        assertEquals("", WHITESPACE.collapseServerWhitespace())
    }

    @Test
    fun `collapsing keeps characters JavaScript trim keeps`() {
        val kept = "\u001C\u001D\u001E\u001F\u200B"

        assertEquals("${kept}Ayat $kept al-Kursi$kept", "${kept}Ayat  $kept\tal-Kursi$kept\n".collapseServerWhitespace())
    }

    @Test
    fun `collection name key trims like the server and lowercases like JavaScript toLowerCase`() {
        assertEquals("study", collectionNameKey("\uFEFF Study\u3000"))
        assertEquals("\u03BF\u03B4\u03BF\u03C2", collectionNameKey("\u039F\u0394\u039F\u03A3"))
        assertEquals("i\u0307", collectionNameKey("\u0130"))
    }
}

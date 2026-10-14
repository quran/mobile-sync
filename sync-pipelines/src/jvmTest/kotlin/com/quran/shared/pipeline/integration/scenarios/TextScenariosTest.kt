package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.TextLengthBound
import com.quran.shared.persistence.TextLengthField
import com.quran.shared.persistence.TextLengthLimitException
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Text handling. The fake's `htmlStrip` is a model of sanitize-html that has
 * not been checked against the real library; expectations that depend on it are marked.
 */
class TextScenariosTest {

    @Test
    fun `TXT-01 note text with ampersands and angle brackets round-trips exactly`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        val typed = "x<y & a>b <b>tag</b> \"q\""

        phone.data.addNote(typed, 1, 1, 1, 7)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        // unverified vs sanitize-html: the client's escaped text decodes and re-escapes unchanged, quotes stay as typed.
        assertEquals(listOf("x&lt;y &amp; a&gt;b &lt;b&gt;tag&lt;/b&gt; \"q\""), server.state(ALICE).notes.map { it.body })
        assertEquals(listOf(typed), phone.noteBodies())
        assertEquals(listOf(typed), tablet.noteBodies())
        listOf(phone, tablet).forEach { device ->
            val run = device.syncNow()
            assertSucceeded(run)
            assertEquals(0, run.posts, "${device.name} pushed again")
        }
    }

    @Test
    fun `TXT-03 whitespace is trimmed like the server, with no re-push`() = syncScenario {
        val (phone) = signedIn("phone")

        phone.data.addNote("\n  Patience is light \t", 2, 153, 2, 153)

        assertEquals(listOf("Patience is light"), phone.noteBodies(), "trimmed locally")
        val runs = listOf(phone.syncNow(), phone.syncNow())
        runs.forEach(::assertSucceeded)
        assertEquals(listOf("Patience is light"), server.state(ALICE).notes.map { it.body })
        assertEquals(1, runs.flatMap { it.accepted }.of("NOTE").size, "NOTE mutations across both syncs")
        assertEquals(listOf("Patience is light"), phone.noteBodies())
    }

    @Test
    fun `TXT-06 collection names escape and round-trip`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.addCollection("Q&A <Tafsir>")
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        // unverified vs sanitize-html: "<Tafsir>" in the client's escaped form is text, not a tag.
        assertEquals(listOf("Q&amp;A &lt;Tafsir&gt;"), server.state(ALICE).collections.map { it.name })
        assertEquals(listOf("Q&A <Tafsir>"), phone.collectionNames())
        assertEquals(listOf("Q&A <Tafsir>"), tablet.collectionNames())
        assertEquals(emptyList(), server.requests.filter { it.status == 409 }, "no 409")
        val again = phone.syncNow()
        assertSucceeded(again)
        assertEquals(0, again.posts, "no re-push")
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `TXT-02 web input with HTML tags`() = syncScenario {
        val (phone) = signedIn("phone")

        web(ALICE).createNote("<b>bold</b> text", "2:255-2:255")
        val runs = listOf(phone.syncNow(), phone.syncNow())

        runs.forEach(::assertSucceeded)
        assertEquals(listOf("bold text"), server.state(ALICE).notes.map { it.body }, "stored tag-stripped")
        assertEquals(listOf("bold text"), phone.noteBodies())
        assertEquals(0, runs.sumOf { it.posts }, "nothing pushed back")
        assertConverged(ALICE, phone)
    }

    @Test
    fun `TXT-04 note length limits are enforced locally after escaping`() = syncScenario {
        val (phone) = signedIn("phone")

        val tooShort = assertFailsWith<TextLengthLimitException> { phone.data.addNote("12345", 1, 1, 1, 1) }
        val tooLong = assertFailsWith<TextLengthLimitException> { phone.data.addNote("a".repeat(9_998) + "&", 1, 2, 1, 2) }
        phone.data.addNote("b".repeat(10_000), 1, 3, 1, 3)

        assertEquals(listOf(TextLengthField.NOTE_BODY, TextLengthBound.TOO_SHORT, 6, 5), tooShort.parts())
        assertEquals(listOf(TextLengthField.NOTE_BODY, TextLengthBound.TOO_LONG, 10_000, 10_003), tooLong.parts())
        assertSucceeded(phone.syncNow())
        assertEquals(listOf(10_000), server.state(ALICE).notes.map { it.body.length })
        assertConverged(ALICE, phone)
    }

    @Test
    fun `TXT-05 length is counted in UTF-16 units like JavaScript`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        val emoji = "😀😀😀"
        val arabic = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ"

        phone.data.addNote(emoji, 1, 1, 1, 1)
        phone.data.addNote(arabic, 1, 1, 1, 7)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        assertEquals(listOf(arabic, emoji).sorted(), server.state(ALICE).notes.map { it.body }.sorted())
        assertEquals(listOf(arabic, emoji).sorted(), tablet.noteBodies())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `TXT-07 collection name limits`() = syncScenario {
        val (phone) = signedIn("phone")
        val longest = "N".repeat(255)

        val empty = assertFailsWith<TextLengthLimitException> { phone.data.addCollection("") }
        val spaces = assertFailsWith<TextLengthLimitException> { phone.data.addCollection("   ") }
        val escaped = assertFailsWith<TextLengthLimitException> { phone.data.addCollection("&".repeat(52)) }
        phone.data.addCollection(longest)

        assertEquals(listOf(TextLengthField.COLLECTION_NAME, TextLengthBound.TOO_SHORT, 1, 0), empty.parts())
        assertEquals(listOf(TextLengthField.COLLECTION_NAME, TextLengthBound.TOO_SHORT, 1, 0), spaces.parts())
        assertEquals(listOf(TextLengthField.COLLECTION_NAME, TextLengthBound.TOO_LONG, 255, 260), escaped.parts())
        assertSucceeded(phone.syncNow())
        assertEquals(listOf(longest), server.state(ALICE).collections.map { it.name })
        assertConverged(ALICE, phone)
    }

    @Test
    fun `TXT-08 a name that differs only in case or whitespace returns the existing collection`() = syncScenario {
        val (phone) = signedIn("phone")
        val study = phone.data.addCollection("Study")
        syncUntilQuiescent(phone)

        val again = phone.data.addCollection("  STUDY ")

        assertEquals(study.id, again.id)
        assertEquals(listOf("Study"), phone.collectionNames())
        val run = phone.syncNow()
        assertSucceeded(run)
        assertEquals(0, run.posts, "nothing extra pushed")
        assertEquals(listOf("Study"), server.state(ALICE).collections.map { it.name })
    }

    private fun TextLengthLimitException.parts(): List<Any> = listOf(field, bound, limit, length)
}

package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Catalog section 2.3: text handling. The fake's `htmlStrip` is a model of sanitize-html that has
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
}

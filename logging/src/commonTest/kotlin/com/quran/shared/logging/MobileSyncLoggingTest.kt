package com.quran.shared.logging

import co.touchlab.kermit.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileSyncLoggingTest {
    private data class Entry(val severity: MobileSyncLogSeverity, val tag: String, val message: String)

    private val entries = mutableListOf<Entry>()

    private fun configure(minSeverity: MobileSyncLogSeverity = MobileSyncLogSeverity.VERBOSE) {
        MobileSyncLogging.configure(
            sink = { severity, tag, message -> entries += Entry(severity, tag, message) },
            minSeverity = minSeverity
        )
    }

    @AfterTest
    fun tearDown() {
        MobileSyncLogging.configure(sink = { _, _, _ -> }, minSeverity = MobileSyncLogSeverity.VERBOSE)
    }

    @Test
    fun `sink receives every severity with its tag`() {
        configure()
        val logger = Logger.withTag("NotesSyncAdapter")

        logger.v { "verbose" }
        logger.d { "debug" }
        logger.i { "info" }
        logger.w { "warn" }
        logger.e { "error" }
        logger.a { "assert" }

        assertEquals(
            listOf(
                Entry(MobileSyncLogSeverity.VERBOSE, "NotesSyncAdapter", "verbose"),
                Entry(MobileSyncLogSeverity.DEBUG, "NotesSyncAdapter", "debug"),
                Entry(MobileSyncLogSeverity.INFO, "NotesSyncAdapter", "info"),
                Entry(MobileSyncLogSeverity.WARN, "NotesSyncAdapter", "warn"),
                Entry(MobileSyncLogSeverity.ERROR, "NotesSyncAdapter", "error"),
                Entry(MobileSyncLogSeverity.ASSERT, "NotesSyncAdapter", "assert"),
            ),
            entries
        )
    }

    @Test
    fun `logs below the minimum severity are dropped`() {
        configure(MobileSyncLogSeverity.DEBUG)
        val logger = Logger.withTag("PostMutationsRequest")

        logger.v { "Request body" }
        logger.d { "Request summary" }

        assertEquals(listOf(Entry(MobileSyncLogSeverity.DEBUG, "PostMutationsRequest", "Request summary")), entries)
    }

    @Test
    fun `loggers created before configure use the sink`() {
        val logger = Logger.withTag("Scheduler")
        configure()

        logger.i { "scheduled" }

        assertEquals(listOf(Entry(MobileSyncLogSeverity.INFO, "Scheduler", "scheduled")), entries)
    }

    @Test
    fun `a logged error's stack trace follows the message`() {
        configure()

        Logger.withTag("QuranDataService").e(IllegalStateException("boom")) { "Logout failed" }

        val entry = entries.single()
        assertEquals(MobileSyncLogSeverity.ERROR, entry.severity)
        assertTrue(entry.message.startsWith("Logout failed\n"))
        assertTrue(entry.message.contains("IllegalStateException: boom"))
    }
}

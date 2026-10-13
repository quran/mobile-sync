package com.quran.shared.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.concurrent.Volatile

enum class MobileSyncLogSeverity {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ASSERT
}

/**
 * Receives Mobile Sync logs, possibly from several threads at once. A logged error's stack trace
 * follows the message on its own lines.
 */
fun interface MobileSyncLogSink {
    fun log(severity: MobileSyncLogSeverity, tag: String, message: String)
}

/** Chooses where Mobile Sync logs go; until [configure] is called, they go to the platform log. */
object MobileSyncLogging {
    @Volatile
    internal var detailedHttpLogs: Boolean = false
        private set

    /**
     * Sends Mobile Sync logs at [minSeverity] or above to [sink].
     *
     * At [MobileSyncLogSeverity.DEBUG] or below, HTTP calls also log headers and bodies. HTTP clients
     * read this when they are created, so call it before `SharedDependencyGraph.init`. This replaces
     * Kermit's process-wide logger configuration, so other Kermit users log there too.
     */
    fun configure(sink: MobileSyncLogSink, minSeverity: MobileSyncLogSeverity) {
        detailedHttpLogs = minSeverity <= MobileSyncLogSeverity.DEBUG
        Logger.setMinSeverity(minSeverity.toKermitSeverity())
        Logger.setLogWriters(SinkLogWriter(sink))
    }
}

internal class SinkLogWriter(private val sink: MobileSyncLogSink) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val text = throwable?.let { "$message\n${it.stackTraceToString()}" } ?: message
        sink.log(severity.toMobileSyncLogSeverity(), tag, text)
    }
}

private fun Severity.toMobileSyncLogSeverity(): MobileSyncLogSeverity = when (this) {
    Severity.Verbose -> MobileSyncLogSeverity.VERBOSE
    Severity.Debug -> MobileSyncLogSeverity.DEBUG
    Severity.Info -> MobileSyncLogSeverity.INFO
    Severity.Warn -> MobileSyncLogSeverity.WARN
    Severity.Error -> MobileSyncLogSeverity.ERROR
    Severity.Assert -> MobileSyncLogSeverity.ASSERT
}

private fun MobileSyncLogSeverity.toKermitSeverity(): Severity = when (this) {
    MobileSyncLogSeverity.VERBOSE -> Severity.Verbose
    MobileSyncLogSeverity.DEBUG -> Severity.Debug
    MobileSyncLogSeverity.INFO -> Severity.Info
    MobileSyncLogSeverity.WARN -> Severity.Warn
    MobileSyncLogSeverity.ERROR -> Severity.Error
    MobileSyncLogSeverity.ASSERT -> Severity.Assert
}

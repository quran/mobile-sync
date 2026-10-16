package com.quran.shared.logging

import kotlin.native.HiddenFromObjC

private const val MAX_CAUSE_DEPTH = 16

/**
 * Describes this failure for a log without any of its messages, which can hold tokens, URLs with their query, server
 * error bodies or the user's email: the class name of each exception along the cause chain, outermost first, with
 * the [details] of each, which must be safe to log (such as an HTTP status).
 *
 * For example `IllegalStateException <- SocketTimeoutException`, or with details `HttpException(status=400)`.
 */
@HiddenFromObjC
fun Throwable.logSummary(details: (Throwable) -> String? = { null }): String =
    causeChain().joinToString(" <- ") { failure ->
        val name = failure::class.simpleName ?: "Throwable"
        details(failure)?.let { "$name($it)" } ?: name
    }

/** This throwable and its causes: at most 16, each once, as a cause can point back up the chain. */
private fun Throwable.causeChain(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && chain.size < MAX_CAUSE_DEPTH && chain.none { it === current }) {
        chain += current
        current = current.cause
    }
    return chain
}

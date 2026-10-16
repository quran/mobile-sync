package com.quran.shared.pipeline

import co.touchlab.kermit.Logger
import com.quran.shared.persistence.DuplicateCollectionNameException
import com.quran.shared.persistence.ReadOnlyCollectionException
import com.quran.shared.persistence.RecordNotFoundException
import com.quran.shared.persistence.ReservedCollectionNameException
import com.quran.shared.persistence.TextLengthLimitException

/**
 * Logs a [QuranDataService] write that failed. Hosts forward warnings and errors to crash reporting, so the rule is:
 *
 * - A rejection logs at info, without the throwable or the user's text. Rejections are the typed exceptions the data
 *   rules raise to answer the user: a note too short, a collection name taken or reserved, a read-only collection, an
 *   id another device deleted, or a write that raced a session reset.
 * - Anything else logs at error with its throwable. That includes plain [IllegalArgumentException]s and
 *   [IllegalStateException]s: a caller misusing the API (a highlight collection passed to a collection API, import
 *   data that breaks its contract) or an internal invariant that broke.
 */
internal fun logWriteFailure(errorMessage: String, failure: Exception) {
    val reason = rejectionReason(failure)
    if (reason == null) {
        Logger.e(failure) { errorMessage }
    } else {
        Logger.i { "$errorMessage: rejected, $reason" }
    }
}

/**
 * Why a write was rejected, in words that hold no user text, or null for a failure that is not a rejection. The
 * collection name exceptions' messages hold the name, so they are described by type only.
 */
private fun rejectionReason(failure: Throwable): String? = when (failure) {
    is TextLengthLimitException -> "${failure.field} ${failure.bound} (length ${failure.length}, limit ${failure.limit})"
    is ReservedCollectionNameException -> "reserved collection name"
    is DuplicateCollectionNameException -> "duplicate collection name"
    is ReadOnlyCollectionException -> "read-only collection"
    // An id that names nothing, such as a note deleted on another device while it was being edited here.
    is RecordNotFoundException -> "${failure.type} id=${failure.id} not found"
    // A write that raced a logout or reset; the reset is logged on its own.
    is SessionResetInProgressException -> "session reset in progress"
    else -> null
}

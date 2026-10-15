package com.quran.shared.pipeline

import co.touchlab.kermit.Logger
import com.quran.shared.persistence.DuplicateCollectionNameException
import com.quran.shared.persistence.ReadOnlyCollectionException
import com.quran.shared.persistence.ReservedCollectionNameException
import com.quran.shared.persistence.TextLengthLimitException

/**
 * Logs a [QuranDataService] write that failed. A write the data rules reject, such as a note too short or a collection
 * name already taken, is an answer to the user, not a fault: it logs at info, without the stack trace or the user's
 * text, since hosts forward warnings and errors to crash reporting. Any other failure logs at error with its throwable.
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
 * collection exceptions' messages hold the name, so they are described by type only.
 *
 * Plain [IllegalArgumentException]s are not rejections: an id that names nothing, a highlight collection passed to a
 * collection API, or import data that breaks its contract are the caller's bugs.
 */
internal fun rejectionReason(failure: Throwable): String? = when (failure) {
    is TextLengthLimitException -> "${failure.field} ${failure.bound} (length ${failure.length}, limit ${failure.limit})"
    is ReservedCollectionNameException -> "reserved collection name"
    is DuplicateCollectionNameException -> "duplicate collection name"
    is ReadOnlyCollectionException -> "read-only collection"
    // A write that raced a logout or reset; the reset is logged on its own.
    is SessionResetInProgressException -> "session reset in progress"
    else -> null
}

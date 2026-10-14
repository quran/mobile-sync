package com.quran.shared.pipeline

/**
 * Why a sync run failed, as a stable category that apps can map to their own localized text.
 */
enum class SyncErrorKind {
    /** The sync server could not be reached, or the connection dropped or timed out. */
    NETWORK,

    /** The sync server did not accept the session (HTTP 401 or 403). */
    AUTHENTICATION,

    /** The sync server failed or was unavailable (HTTP 5xx, 408, or 429). */
    SERVER,

    /** The sync server rejected the request (any other HTTP 4xx). */
    REJECTED,

    /** Any other failure, such as an invalid response or a local storage error. */
    UNKNOWN
}

/**
 * A failed sync run, safe to show to users and to log.
 *
 * It never carries response bodies, server messages, or user data.
 *
 * @param kind the stable failure category.
 * @param httpStatus the HTTP status code when the server answered with an error, or `null`.
 * @param serverCode the server's error identifier, such as `UniqueViolationError`, or `null`.
 * @param message a short English description for diagnostics; apps should localize from [kind].
 */
data class SyncError(
    val kind: SyncErrorKind,
    val httpStatus: Int?,
    val serverCode: String?,
    val message: String
)

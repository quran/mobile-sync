package com.quran.shared.auth.utils

import com.quran.shared.logging.logSummary
import io.ktor.http.HttpStatusCode
import kotlin.native.HiddenFromObjC
import org.publicvalue.multiplatform.oidc.HttpException
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import org.publicvalue.multiplatform.oidc.types.remote.ErrorResponse

/**
 * Describes this failure for a log without any of its messages, which can hold tokens, authorization codes, URLs
 * with their query, server error bodies or the user's email: the class name of each exception along the cause
 * chain, with the HTTP status and the OAuth error code (such as `invalid_grant`) where a failure carries them.
 *
 * The OIDC library reports a refused refresh token, for example, as
 * `UnsuccessfulTokenRequest(status=400, error=invalid_grant) <- HttpException(status=400, error=invalid_grant)`.
 */
@HiddenFromObjC
fun Throwable.authFailureSummary(): String = logSummary { failure ->
    when (failure) {
        is OpenIdConnectException.UnsuccessfulTokenRequest -> httpDetails(failure.statusCode, failure.errorResponse)
        is HttpException -> httpDetails(failure.statusCode, failure.errorResponse)
        else -> null
    }
}

// ErrorResponse.Error is a closed enum of the OAuth error codes, so its name is never the server's free text.
private fun httpDetails(status: HttpStatusCode, errorResponse: ErrorResponse?): String =
    listOfNotNull("status=${status.value}", errorResponse?.let { "error=${it.error.name}" }).joinToString(", ")

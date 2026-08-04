package com.quran.shared.auth.model

import kotlin.native.HiddenFromObjC
import kotlinx.io.IOException
import org.publicvalue.multiplatform.oidc.OpenIdConnectException

/** Stable authentication failures exported to managed clients. */
sealed class AuthenticationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class AuthenticationCancelledException(
    cause: Throwable? = null
) : AuthenticationException("Authentication cancelled", cause)

class AuthenticationNetworkException(
    cause: Throwable
) : AuthenticationException(cause.message ?: "Authentication network request failed", cause)

class AuthenticationFailedException(
    cause: Throwable
) : AuthenticationException(cause.message ?: "Authentication failed", cause)

@HiddenFromObjC
fun Exception.asAuthenticationException(): AuthenticationException =
    when {
        this is AuthenticationException -> this
        this is OpenIdConnectException.AuthenticationCancelled -> AuthenticationCancelledException(this)
        hasNetworkCause() -> AuthenticationNetworkException(this)
        else -> AuthenticationFailedException(this)
    }

private fun Throwable.hasNetworkCause(): Boolean {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        when (val cause = current) {
            null -> return false
            is IOException -> return true
            else -> current = cause.cause.takeUnless { it === cause }
        }
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 16

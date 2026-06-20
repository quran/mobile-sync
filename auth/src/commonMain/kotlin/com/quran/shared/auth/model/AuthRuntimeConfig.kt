package com.quran.shared.auth.model

import kotlin.native.HiddenFromObjC

/**
 * Runtime authentication mode for a managed mobile-sync graph.
 *
 * [Configured] is used when the consuming app provides valid OIDC metadata and interactive
 * authentication can run. [Unconfigured] keeps the managed graph usable for local-first data in
 * open-source or otherwise uncredentialed builds while making sign-in unavailable.
 */
@HiddenFromObjC
sealed interface AuthRuntimeConfig {
    /**
     * Authentication is backed by the supplied OIDC configuration.
     */
    data class Configured(val config: AuthConfig) : AuthRuntimeConfig

    /**
     * Authentication credentials are not available for this build.
     */
    data object Unconfigured : AuthRuntimeConfig

    /**
     * Returns true when interactive authentication can be attempted.
     */
    val isConfigured: Boolean
        get() = this is Configured
}

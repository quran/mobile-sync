package com.quran.shared.pipeline

import com.quran.shared.auth.model.AuthEnvironment
import com.quran.shared.auth.model.defaultAuthEnvironment

enum class AppEnvironment(
    val authEnvironment: AuthEnvironment,
    private val syncBaseUrl: String
) {
    PRELIVE(
        authEnvironment = AuthEnvironment.PRELIVE,
        syncBaseUrl = "https://apis-prelive.quran.foundation/auth"
    ),
    PRODUCTION(
        authEnvironment = AuthEnvironment.PRODUCTION,
        syncBaseUrl = "https://apis.quran.foundation/auth"
    );

    fun synchronizationEnvironment(): SynchronizationEnvironment {
        return SynchronizationEnvironment(endPointURL = syncBaseUrl)
    }
}

fun defaultAppEnvironment(): AppEnvironment {
    return when (defaultAuthEnvironment()) {
        AuthEnvironment.PRELIVE -> AppEnvironment.PRELIVE
        AuthEnvironment.PRODUCTION -> AppEnvironment.PRODUCTION
    }
}

/** The sync server: [endPointURL] is the base URL the sync API paths are resolved against. */
data class SynchronizationEnvironment(val endPointURL: String)

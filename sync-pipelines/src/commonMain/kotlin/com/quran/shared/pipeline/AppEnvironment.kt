package com.quran.shared.pipeline

import com.quran.shared.auth.model.AuthEnvironment
import com.quran.shared.auth.model.defaultAuthEnvironment

enum class AppEnvironment(
    val authEnvironment: AuthEnvironment,
    private val syncBaseUrl: String,
    private val avatarsHost: String
) {
    PRELIVE(
        authEnvironment = AuthEnvironment.PRELIVE,
        syncBaseUrl = "https://apis-prelive.quran.foundation/auth",
        // Unconfirmed: prelive's avatar host couldn't be checked (prelive QuranReflect was down on 2026-10-29), so it
        // assumes production's bucket.
        avatarsHost = PRODUCTION_AVATARS_HOST
    ),
    PRODUCTION(
        authEnvironment = AuthEnvironment.PRODUCTION,
        syncBaseUrl = "https://apis.quran.foundation/auth",
        avatarsHost = PRODUCTION_AVATARS_HOST
    );

    fun synchronizationEnvironment(): SynchronizationEnvironment {
        return SynchronizationEnvironment(endPointURL = syncBaseUrl, avatarsHost = avatarsHost)
    }
}

fun defaultAppEnvironment(): AppEnvironment {
    return when (defaultAuthEnvironment()) {
        AuthEnvironment.PRELIVE -> AppEnvironment.PRELIVE
        AuthEnvironment.PRODUCTION -> AppEnvironment.PRODUCTION
    }
}

/**
 * The sync server: [endPointURL] is the base URL the sync API paths (and the account's profile) are resolved against.
 * [avatarsHost] serves the photos users upload on Quran.com (the users backend's `AVATARS_HOST`).
 */
data class SynchronizationEnvironment(
    val endPointURL: String,
    val avatarsHost: String = PRODUCTION_AVATARS_HOST
)

/** Production's `AVATARS_HOST`, read from live avatar URLs on quranreflect.com (2026-10-29). */
private const val PRODUCTION_AVATARS_HOST = "https://s3.amazonaws.com/qreflect-bucket"

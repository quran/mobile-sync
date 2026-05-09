package com.quran.shared.persistence.util

internal fun PlatformDateTime?.toEpochMillisecondsOrNull(): Long? {
    return this?.fromPlatform()?.toEpochMilliseconds()
}

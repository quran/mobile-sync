package com.quran.shared.persistence.util

import kotlin.time.Clock

/**
 * Returns the current system time converted to the platform date type used by public APIs.
 */
fun currentPlatformDateTime(): PlatformDateTime {
    return Clock.System.now().toPlatform()
}

internal fun currentEpochMilliseconds(): Long {
    return Clock.System.now().toEpochMilliseconds()
}

internal fun PlatformDateTime.toEpochMillisecondsFromPlatform(): Long {
    return fromPlatform().toEpochMilliseconds()
}

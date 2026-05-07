package com.quran.shared.persistence.util

import kotlinx.datetime.toNSDate
import platform.Foundation.NSTimeIntervalSince1970
import platform.Foundation.NSDate
import kotlin.math.roundToLong
import kotlin.time.Instant

actual typealias PlatformDateTime = NSDate

actual fun PlatformDateTime.fromPlatform(): Instant {
    val epochSeconds = timeIntervalSinceReferenceDate + NSTimeIntervalSince1970
    val epochMilliseconds = (epochSeconds * 1000).roundToLong()
    return Instant.fromEpochMilliseconds(epochMilliseconds)
}

actual fun Instant.toPlatform(): PlatformDateTime = toNSDate()

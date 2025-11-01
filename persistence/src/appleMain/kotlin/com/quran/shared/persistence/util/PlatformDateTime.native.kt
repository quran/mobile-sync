package com.quran.shared.persistence.util

import kotlinx.datetime.toKotlinInstant
import kotlinx.datetime.toNSDate
import platform.Foundation.NSDate
import kotlin.time.Instant

actual typealias PlatformDateTime = NSDate
actual fun PlatformDateTime.fromPlatform(): Instant = toKotlinInstant()
actual fun Instant.toPlatform(): PlatformDateTime = toNSDate()
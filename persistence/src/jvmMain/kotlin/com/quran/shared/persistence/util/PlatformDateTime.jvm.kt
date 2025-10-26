package com.quran.shared.persistence.util

import kotlin.time.Instant

actual typealias PlatformDateTime = Instant
actual fun PlatformDateTime.fromPlatform(): Instant = this
actual fun PlatformDateTime.toPlatform(): PlatformDateTime = this
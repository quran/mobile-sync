package com.quran.shared.persistence.util

import kotlin.time.Instant

expect class PlatformDateTime
expect fun PlatformDateTime.fromPlatform(): Instant
expect fun Instant.toPlatform(): PlatformDateTime
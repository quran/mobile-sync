package com.quran.shared.persistence.input

import com.quran.shared.persistence.util.PlatformDateTime

data class RemoteCollection(
    val name: String?,
    val lastUpdated: PlatformDateTime
)

package com.quran.shared.persistence.input

import com.quran.shared.persistence.util.PlatformDateTime

data class RemoteNote(
    val body: String?,
    val startSura: Int?,
    val startAyah: Int?,
    val endSura: Int?,
    val endAyah: Int?,
    val lastUpdated: PlatformDateTime,
    val semanticReplayEligible: Boolean = true,
    val createdAt: PlatformDateTime? = null
)

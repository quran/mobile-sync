package com.quran.shared.persistence.input

import com.quran.shared.persistence.util.PlatformDateTime

data class RemoteNote(
    val body: String?,
    val startAyahId: Long?,
    val endAyahId: Long?,
    val lastUpdated: PlatformDateTime
)

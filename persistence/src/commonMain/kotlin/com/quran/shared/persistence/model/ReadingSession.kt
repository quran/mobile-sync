package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

data class ReadingSession(
    val sura: Int,
    val ayah: Int,
    val lastUpdated: PlatformDateTime,
    val localId: String
)

package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

data class Note(
    val body: String,
    val startSura: Int,
    val startAyah: Int,
    val endSura: Int,
    val endAyah: Int,
    val lastUpdated: PlatformDateTime,
    val localId: String
)

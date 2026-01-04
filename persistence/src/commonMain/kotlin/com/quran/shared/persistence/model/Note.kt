package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

data class Note(
    val body: String,
    val startAyahId: Long,
    val endAyahId: Long,
    val lastUpdated: PlatformDateTime,
    val localId: String
)

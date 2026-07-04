package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

data class AyahBookmark(
    val sura: Int,
    val ayah: Int,
    val localId: String,
    val lastUpdated: PlatformDateTime,
    val addedDate: PlatformDateTime = lastUpdated
)

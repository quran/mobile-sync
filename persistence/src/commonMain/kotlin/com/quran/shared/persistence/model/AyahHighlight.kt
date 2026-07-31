package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

data class AyahHighlight(
    val sura: Int,
    val ayah: Int,
    val color: AyahHighlightColor,
    val lastUpdated: PlatformDateTime
)

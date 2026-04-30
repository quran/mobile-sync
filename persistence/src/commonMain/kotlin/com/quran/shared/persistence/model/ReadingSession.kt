package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

data class ReadingSession(
    val chapterNumber: Int,
    val verseNumber: Int,
    val lastUpdated: PlatformDateTime,
    val localId: String
)

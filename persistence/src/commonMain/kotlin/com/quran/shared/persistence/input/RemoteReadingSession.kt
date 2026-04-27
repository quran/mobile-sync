package com.quran.shared.persistence.input

import com.quran.shared.persistence.util.PlatformDateTime

data class RemoteReadingSession(
    val page: Int,
    val chapterNumber: Int,
    val verseNumber: Int,
    val lastUpdated: PlatformDateTime
)

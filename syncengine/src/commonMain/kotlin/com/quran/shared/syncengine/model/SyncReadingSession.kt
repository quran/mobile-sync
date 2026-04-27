package com.quran.shared.syncengine.model

import kotlin.time.Instant

data class SyncReadingSession(
    val id: String,
    val page: Int,
    val chapterNumber: Int,
    val verseNumber: Int,
    val lastModified: Instant
)

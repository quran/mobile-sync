package com.quran.shared.persistence.model

import kotlinx.datetime.Instant

data class PageBookmark(
    val page: Int,
    val lastUpdated: Instant,
    val localId: String?
)
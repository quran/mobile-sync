@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.quran.shared.persistence.model

import kotlin.time.Instant

data class PageBookmark(
    val page: Int,
    val lastUpdated: Instant,
    val localId: String?
)
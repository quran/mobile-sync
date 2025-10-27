package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

data class PageBookmark(
    val page: Int,
    val lastUpdated: PlatformDateTime,
    val localId: String?
)
package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

data class RecentPage(
    val page: Int,
    val lastUpdated: PlatformDateTime,
    val localId: String
)

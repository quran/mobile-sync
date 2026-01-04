package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

data class Collection(
    val name: String,
    val lastUpdated: PlatformDateTime,
    val localId: String
)

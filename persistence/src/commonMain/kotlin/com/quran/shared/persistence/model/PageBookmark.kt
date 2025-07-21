package com.quran.shared.persistence.model

data class PageBookmark(
    val page: Int,
    val lastUpdated: Long,
    val localId: String?
)
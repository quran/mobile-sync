package com.quran.shared.persistence.model

data class PageBookmark(
    val page: Int,
    val lastUpdated: Long,
    val remoteId: String? = null,
    val localId: String? = null
)
package com.quran.shared.persistence.model

data class Bookmark(
    val id: Long,
    val sura: Int?,
    val ayah: Int?,
    val page: Int?,
    val remoteId: String?,
    val lastUpdated: Long
) {
    val isPageBookmark: Boolean
        get() = page != null && sura == null && ayah == null

    val isAyahBookmark: Boolean
        get() = sura != null && ayah != null && page == null
} 
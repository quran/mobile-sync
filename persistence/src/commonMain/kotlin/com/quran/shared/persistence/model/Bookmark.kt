package com.quran.shared.persistence.model

enum class BookmarkLocalMutation {
    NONE,
    CREATED,
    DELETED
}

data class Bookmark(
    val id: Long,
    val sura: Int?,
    val ayah: Int?,
    val page: Int?,
    val remoteId: String?,
    val localMutation: BookmarkLocalMutation,
    val lastUpdated: Long
) {
    val isPageBookmark: Boolean
        get() = page != null && sura == null && ayah == null

    val isAyahBookmark: Boolean
        get() = sura != null && ayah != null && page == null
}
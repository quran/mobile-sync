package com.quran.shared.persistence.model

enum class BookmarkLocalMutation {
    NONE,
    CREATED,
    DELETED
}

sealed class Bookmark {
    abstract val remoteId: String?
    abstract val localMutation: BookmarkLocalMutation
    abstract val lastUpdated: Long

    data class PageBookmark(
        val page: Int,
        override val remoteId: String? = null,
        override val localMutation: BookmarkLocalMutation = BookmarkLocalMutation.NONE,
        override val lastUpdated: Long
    ) : Bookmark()

    data class AyahBookmark(
        val sura: Int,
        val ayah: Int,
        override val remoteId: String? = null,
        override val localMutation: BookmarkLocalMutation = BookmarkLocalMutation.NONE,
        override val lastUpdated: Long
    ) : Bookmark()
}
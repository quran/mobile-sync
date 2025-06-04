package com.quran.shared.persistence.model

sealed class Bookmark {
    abstract val remoteId: String?
    abstract val lastUpdated: Long

    data class PageBookmark(
        val page: Int,
        override val remoteId: String? = null,
        override val lastUpdated: Long
    ) : Bookmark()

    data class AyahBookmark(
        val sura: Int,
        val ayah: Int,
        override val remoteId: String? = null,
        override val lastUpdated: Long
    ) : Bookmark()
}
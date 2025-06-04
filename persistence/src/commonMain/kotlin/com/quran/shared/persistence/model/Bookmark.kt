package com.quran.shared.persistence.model

sealed class Bookmark {
    abstract val lastUpdated: Long
    internal abstract val remoteId: String?

    data class PageBookmark(
        val page: Int,
        override val lastUpdated: Long,
        override val remoteId: String? = null
    ) : Bookmark()

    data class AyahBookmark(
        val sura: Int,
        val ayah: Int,
        override val lastUpdated: Long,
        override val remoteId: String? = null
    ) : Bookmark()
}
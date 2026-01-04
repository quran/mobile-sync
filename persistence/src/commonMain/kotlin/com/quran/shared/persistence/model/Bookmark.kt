package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

sealed class Bookmark {
    abstract val localId: String

    data class PageBookmark(
        val page: Int,
        val lastUpdated: PlatformDateTime,
        override val localId: String
    ) : Bookmark()

    data class AyahBookmark(
        val sura: Int,
        val ayah: Int,
        val lastUpdated: PlatformDateTime,
        override val localId: String
    ) : Bookmark()
}

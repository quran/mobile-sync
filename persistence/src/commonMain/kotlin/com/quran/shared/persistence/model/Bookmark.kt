package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

sealed class Bookmark {
    abstract val localId: String
    abstract val isReading: Boolean
    abstract val lastUpdated: PlatformDateTime

    data class PageBookmark(
        val page: Int,
        override val isReading: Boolean = false,
        override val lastUpdated: PlatformDateTime,
        override val localId: String
    ) : Bookmark()

    data class AyahBookmark(
        val sura: Int,
        val ayah: Int,
        override val isReading: Boolean = false,
        override val lastUpdated: PlatformDateTime,
        override val localId: String
    ) : Bookmark()
}

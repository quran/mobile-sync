package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

sealed class Bookmark {
    abstract val sura: Int
    abstract val ayah: Int
    abstract val localId: String
    abstract val lastUpdated: PlatformDateTime

    data class AyahBookmark(
        override val sura: Int,
        override val ayah: Int,
        override val lastUpdated: PlatformDateTime,
        override val localId: String
    ) : Bookmark()
}

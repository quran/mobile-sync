package com.quran.shared.persistence.input

import com.quran.shared.persistence.util.PlatformDateTime

sealed class RemoteBookmark {
    abstract val isReading: Boolean
    abstract val lastUpdated: PlatformDateTime
    abstract val createdAt: PlatformDateTime?

    data class Page(
        val page: Int,
        override val isReading: Boolean,
        override val lastUpdated: PlatformDateTime,
        override val createdAt: PlatformDateTime? = null
    ) : RemoteBookmark()

    data class Ayah(
        val sura: Int,
        val ayah: Int,
        override val isReading: Boolean,
        override val lastUpdated: PlatformDateTime,
        override val createdAt: PlatformDateTime? = null
    ) : RemoteBookmark()
}

package com.quran.shared.persistence.input

import com.quran.shared.persistence.util.PlatformDateTime

sealed class RemoteBookmark {
    abstract val lastUpdated: PlatformDateTime
    abstract val createdAt: PlatformDateTime?

    data class Ayah(
        val sura: Int,
        val ayah: Int,
        override val lastUpdated: PlatformDateTime,
        override val createdAt: PlatformDateTime? = null
    ) : RemoteBookmark()
}

package com.quran.shared.persistence.input

import com.quran.shared.persistence.util.PlatformDateTime

sealed class RemoteBookmark {
    data class Page(val page: Int, val lastUpdated: PlatformDateTime) : RemoteBookmark()
    data class Ayah(
        val sura: Int,
        val ayah: Int,
        val lastUpdated: PlatformDateTime
    ) : RemoteBookmark()
}

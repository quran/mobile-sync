package com.quran.shared.syncengine.model

import kotlin.time.Instant

data class SyncReadingBookmark(
    val slot: Int,
    val name: String?,
    val location: SyncReadingBookmarkLocation?,
    val lastModified: Instant,
    val createdAt: Instant?
)

sealed interface SyncReadingBookmarkLocation {
    data class Ayah(
        val sura: Int,
        val ayah: Int
    ) : SyncReadingBookmarkLocation

    data class Page(
        val page: Int
    ) : SyncReadingBookmarkLocation
}

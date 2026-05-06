package com.quran.shared.syncengine.model

import kotlin.time.Instant

sealed class SyncBookmark {
    abstract val lastModified: Instant
    abstract val isReading: Boolean

    data class AyahBookmark(
        val id: String,
        val sura: Int,
        val ayah: Int,
        override val isReading: Boolean,
        override val lastModified: Instant
    ) : SyncBookmark()

    data class PageBookmark(
        val id: String,
        val page: Int,
        override val isReading: Boolean,
        override val lastModified: Instant
    ) : SyncBookmark()
}

internal sealed class SyncBookmarkKey {
    data class Ayah(val sura: Int, val ayah: Int, val isReading: Boolean) : SyncBookmarkKey() {
        override fun toString(): String = "sura=$sura, ayah=$ayah, isReading=$isReading"
    }

    data class Page(val page: Int, val isReading: Boolean) : SyncBookmarkKey() {
        override fun toString(): String = "page=$page, isReading=$isReading"
    }
}

internal fun SyncBookmark.conflictKeyOrNull(): SyncBookmarkKey {
    return when (this) {
        is SyncBookmark.AyahBookmark -> SyncBookmarkKey.Ayah(sura, ayah, isReading)
        is SyncBookmark.PageBookmark -> SyncBookmarkKey.Page(page, isReading)
    }
}

internal fun SyncBookmark.conflictKey(): SyncBookmarkKey {
    return conflictKeyOrNull()
}

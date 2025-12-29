package com.quran.shared.syncengine.model

import kotlin.time.Instant

sealed class SyncBookmark {
    data class PageBookmark(val id: String, val page: Int, val lastModified: Instant) :
        SyncBookmark()

    data class AyahBookmark(
        val id: String,
        val sura: Int,
        val ayah: Int,
        val lastModified: Instant
    ) : SyncBookmark()
}

internal sealed class SyncBookmarkKey {
    data class Page(val page: Int) : SyncBookmarkKey() {
        override fun toString(): String = "page=$page"
    }

    data class Ayah(val sura: Int, val ayah: Int) : SyncBookmarkKey() {
        override fun toString(): String = "sura=$sura, ayah=$ayah"
    }
}

internal fun SyncBookmark.conflictKeyOrNull(): SyncBookmarkKey {
    return when (this) {
        is SyncBookmark.PageBookmark -> SyncBookmarkKey.Page(page)
        is SyncBookmark.AyahBookmark -> SyncBookmarkKey.Ayah(sura, ayah)
    }
}

internal fun SyncBookmark.conflictKey(): SyncBookmarkKey {
    return conflictKeyOrNull()
}

package com.quran.shared.syncengine.model

import kotlin.time.Instant

sealed class SyncBookmark {
    abstract val lastModified: Instant
    abstract val createdAt: Instant?

    data class AyahBookmark(
        val id: String,
        val sura: Int,
        val ayah: Int,
        override val lastModified: Instant,
        override val createdAt: Instant? = null
    ) : SyncBookmark()
}

internal sealed class SyncBookmarkKey {
    data class Ayah(val sura: Int, val ayah: Int) : SyncBookmarkKey() {
        override fun toString(): String = "sura=$sura, ayah=$ayah"
    }
}

internal fun SyncBookmark.conflictKeyOrNull(): SyncBookmarkKey {
    return when (this) {
        is SyncBookmark.AyahBookmark -> SyncBookmarkKey.Ayah(sura, ayah)
    }
}

internal fun SyncBookmark.conflictKey(): SyncBookmarkKey {
    return conflictKeyOrNull()
}

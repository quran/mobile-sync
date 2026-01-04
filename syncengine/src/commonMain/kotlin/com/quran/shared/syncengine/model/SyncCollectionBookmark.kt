package com.quran.shared.syncengine.model

import kotlin.time.Instant

sealed class SyncCollectionBookmark {
    abstract val collectionId: String
    abstract val lastModified: Instant

    data class PageBookmark(
        override val collectionId: String,
        val page: Int,
        override val lastModified: Instant,
        val bookmarkId: String? = null
    ) : SyncCollectionBookmark()

    data class AyahBookmark(
        override val collectionId: String,
        val sura: Int,
        val ayah: Int,
        override val lastModified: Instant,
        val bookmarkId: String? = null
    ) : SyncCollectionBookmark()
}

internal sealed class SyncCollectionBookmarkKey {
    data class Page(val collectionId: String, val page: Int) : SyncCollectionBookmarkKey() {
        override fun toString(): String = "collection=$collectionId, page=$page"
    }

    data class Ayah(val collectionId: String, val sura: Int, val ayah: Int) : SyncCollectionBookmarkKey() {
        override fun toString(): String = "collection=$collectionId, sura=$sura, ayah=$ayah"
    }
}

internal fun SyncCollectionBookmark.conflictKey(): SyncCollectionBookmarkKey {
    return when (this) {
        is SyncCollectionBookmark.PageBookmark -> SyncCollectionBookmarkKey.Page(collectionId, page)
        is SyncCollectionBookmark.AyahBookmark -> SyncCollectionBookmarkKey.Ayah(collectionId, sura, ayah)
    }
}

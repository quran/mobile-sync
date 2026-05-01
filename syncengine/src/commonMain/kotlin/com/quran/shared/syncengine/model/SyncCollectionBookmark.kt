package com.quran.shared.syncengine.model

import kotlin.time.Instant

sealed class SyncCollectionBookmark {
    abstract val collectionId: String
    abstract val lastModified: Instant

    data class AyahBookmark(
        override val collectionId: String,
        val sura: Int,
        val ayah: Int,
        override val lastModified: Instant,
        val bookmarkId: String? = null
    ) : SyncCollectionBookmark()
}

fun collectionBookmarkRemoteId(collectionId: String, bookmarkId: String): String {
    return "$collectionId::$bookmarkId"
}

fun SyncCollectionBookmark.remoteIdOrNull(): String? {
    return when (this) {
        is SyncCollectionBookmark.AyahBookmark ->
            bookmarkId?.let { collectionBookmarkRemoteId(collectionId, it) }
    }
}

internal sealed class SyncCollectionBookmarkKey {
    data class Ayah(val collectionId: String, val sura: Int, val ayah: Int) : SyncCollectionBookmarkKey() {
        override fun toString(): String = "collection=$collectionId, sura=$sura, ayah=$ayah"
    }
}

internal fun SyncCollectionBookmark.conflictKey(): SyncCollectionBookmarkKey {
    return when (this) {
        is SyncCollectionBookmark.AyahBookmark -> SyncCollectionBookmarkKey.Ayah(collectionId, sura, ayah)
    }
}

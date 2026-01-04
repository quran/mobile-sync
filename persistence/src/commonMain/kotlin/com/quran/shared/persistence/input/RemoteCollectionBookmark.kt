package com.quran.shared.persistence.input

import com.quran.shared.persistence.util.PlatformDateTime

sealed class RemoteCollectionBookmark {
    abstract val collectionId: String
    abstract val lastUpdated: PlatformDateTime
    abstract val bookmarkId: String?

    data class Page(
        override val collectionId: String,
        val page: Int,
        override val lastUpdated: PlatformDateTime,
        override val bookmarkId: String? = null
    ) : RemoteCollectionBookmark()

    data class Ayah(
        override val collectionId: String,
        val sura: Int,
        val ayah: Int,
        override val lastUpdated: PlatformDateTime,
        override val bookmarkId: String? = null
    ) : RemoteCollectionBookmark()
}

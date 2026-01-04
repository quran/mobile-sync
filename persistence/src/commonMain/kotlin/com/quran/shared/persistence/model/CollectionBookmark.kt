package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

sealed class CollectionBookmark {
    abstract val collectionLocalId: String
    abstract val collectionRemoteId: String?
    abstract val bookmarkLocalId: String
    abstract val lastUpdated: PlatformDateTime
    abstract val localId: String

    data class PageBookmark(
        override val collectionLocalId: String,
        override val collectionRemoteId: String?,
        override val bookmarkLocalId: String,
        val page: Int,
        override val lastUpdated: PlatformDateTime,
        override val localId: String
    ) : CollectionBookmark()

    data class AyahBookmark(
        override val collectionLocalId: String,
        override val collectionRemoteId: String?,
        override val bookmarkLocalId: String,
        val sura: Int,
        val ayah: Int,
        override val lastUpdated: PlatformDateTime,
        override val localId: String
    ) : CollectionBookmark()
}

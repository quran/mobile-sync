package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

data class CollectionAyahBookmark(
    val collectionLocalId: String,
    val collectionRemoteId: String?,
    val bookmarkLocalId: String,
    val bookmarkRemoteId: String?,
    val sura: Int,
    val ayah: Int,
    val lastUpdated: PlatformDateTime,
    val localId: String
)

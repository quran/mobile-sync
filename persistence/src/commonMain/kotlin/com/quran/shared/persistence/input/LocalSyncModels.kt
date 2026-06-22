package com.quran.shared.persistence.input

import com.quran.shared.persistence.util.PlatformDateTime
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
data class LocalSyncCollection(
    val name: String,
    val lastUpdated: PlatformDateTime,
    val localId: String,
    val createdAt: PlatformDateTime
)

@HiddenFromObjC
data class LocalSyncCollectionAyahBookmark(
    val collectionLocalId: String,
    val collectionRemoteId: String?,
    val bookmarkLocalId: String,
    val bookmarkRemoteId: String?,
    val sura: Int,
    val ayah: Int,
    val lastUpdated: PlatformDateTime,
    val localId: String,
    val createdAt: PlatformDateTime?
)

@HiddenFromObjC
data class LocalSyncNote(
    val body: String,
    val startSura: Int,
    val startAyah: Int,
    val endSura: Int,
    val endAyah: Int,
    val lastUpdated: PlatformDateTime,
    val localId: String,
    val createdAt: PlatformDateTime
)

@HiddenFromObjC
data class LocalSyncReadingSession(
    val sura: Int,
    val ayah: Int,
    val lastUpdated: PlatformDateTime,
    val localId: String,
    val createdAt: PlatformDateTime
)

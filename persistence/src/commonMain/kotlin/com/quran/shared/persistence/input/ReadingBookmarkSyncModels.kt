package com.quran.shared.persistence.input

import com.quran.shared.persistence.util.PlatformDateTime
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
data class LocalSyncReadingBookmark(
    val slot: Int,
    val name: String?,
    val type: String?,
    val sura: Int?,
    val ayah: Int?,
    val page: Int?,
    val lastUpdated: PlatformDateTime,
    val localId: String,
    val createdAt: PlatformDateTime
)

data class RemoteReadingBookmark(
    val slot: Int,
    val name: String?,
    val type: String?,
    val sura: Int?,
    val ayah: Int?,
    val page: Int?,
    val lastUpdated: PlatformDateTime,
    val createdAt: PlatformDateTime?
)

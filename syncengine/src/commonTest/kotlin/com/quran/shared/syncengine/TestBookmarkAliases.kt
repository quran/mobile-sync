@file:Suppress("FunctionName")

package com.quran.shared.syncengine

import com.quran.shared.syncengine.model.SyncBookmark
import kotlin.time.Instant

fun PageBookmark(
    id: String,
    page: Int,
    lastModified: Instant
): SyncBookmark.AyahBookmark {
    return SyncBookmark.AyahBookmark(
        id = id,
        sura = page,
        ayah = 1,
        lastModified = lastModified
    )
}

val SyncBookmark.AyahBookmark.page: Int
    get() = sura

package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

/**
 * App-facing note attached to a Quran ayah range.
 *
 * @param body the text of the note
 * @param startSura the start sura
 * @param startAyah the start ayah
 * @param endSura the end sura
 * @param endAyah the end ayah
 * @param lastUpdated when the note was last updated
 * @param id the identifier of the note
 */
data class Note(
    val body: String,
    val startSura: Int,
    val startAyah: Int,
    val endSura: Int,
    val endAyah: Int,
    val lastUpdated: PlatformDateTime,
    val id: String
)

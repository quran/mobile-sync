package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

/**
 * App-facing reading-session entry.
 *
 * @param sura the sura
 * @param ayah the ayah
 * @param lastUpdated the last updated time of the reading session
 * @param id the identifier of the reading session
 */
data class ReadingSession(
    val sura: Int,
    val ayah: Int,
    val lastUpdated: PlatformDateTime,
    val id: String
)

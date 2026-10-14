package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

/**
 * App-facing note attached to one or more Quran ayah ranges.
 *
 * @param body the text of the note
 * @param ranges the note's verse ranges, never empty, in the order the note lists them. A note created in the app
 *   has one; a note created on Quran.com may have several.
 * @param lastUpdated when the note was last updated
 * @param id the identifier of the note
 */
data class Note(
    val body: String,
    val ranges: List<NoteRange>,
    val lastUpdated: PlatformDateTime,
    val id: String
)

/**
 * A note's verse range, from [startSura]:[startAyah] to [endSura]:[endAyah].
 */
data class NoteRange(
    val startSura: Int,
    val startAyah: Int,
    val endSura: Int,
    val endAyah: Int
)

package com.quran.shared.syncengine.model

import kotlin.time.Instant

data class SyncNote(
    val id: String,
    val body: String?,
    val ranges: List<NoteRange>,
    val lastModified: Instant
)

data class NoteAyah(
    val sura: Int,
    val ayah: Int
)

data class NoteRange(
    val start: NoteAyah,
    val end: NoteAyah
)

internal fun NoteRange.toRangeString(): String {
    return "${start.sura}:${start.ayah}-${end.sura}:${end.ayah}"
}

internal fun parseNoteRange(value: String): NoteRange? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return null
    }

    val parts = trimmed.split("-", limit = 2)
    val start = parseNoteAyah(parts.first()) ?: return null
    val end = if (parts.size > 1) {
        parseNoteAyah(parts[1]) ?: return null
    } else {
        start
    }

    return NoteRange(start = start, end = end)
}

private fun parseNoteAyah(value: String): NoteAyah? {
    val trimmed = value.trim()
    val pieces = trimmed.split(":", limit = 2)
    if (pieces.size != 2) {
        return null
    }
    val sura = pieces[0].toIntOrNull() ?: return null
    val ayah = pieces[1].toIntOrNull() ?: return null
    return NoteAyah(sura = sura, ayah = ayah)
}

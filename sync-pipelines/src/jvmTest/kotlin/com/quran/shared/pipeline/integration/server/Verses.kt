package com.quran.shared.pipeline.integration.server

internal object Verses {
    private val COUNTS = intArrayOf(
        7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110, 98, 135,
        112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53,
        89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12,
        12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25, 22, 17, 19, 26,
        30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6
    )

    fun exists(sura: Int, ayah: Int): Boolean = sura in 1..114 && ayah in 1..COUNTS[sura - 1]

    /** The verse's index in the whole Quran, 1..6236. */
    fun index(sura: Int, ayah: Int): Int = (0 until sura - 1).sumOf { COUNTS[it] } + ayah

    private val RANGE = Regex("^(\\d+):(\\d+)-(\\d+):(\\d+)$")

    /**
     * `rangesToIndices`: a range the Joi regex accepted can still name verses that don't exist, use
     * leading zeros, or run backwards. The backend then throws an untyped error, which surfaces as
     * a 500 `ServerError` (S11).
     */
    fun normalizeRange(range: String): String {
        val parts = RANGE.matchEntire(range)?.groupValues?.drop(1) ?: error("Invalid verse range: $range")
        if (parts.any { it.length > 1 && it.startsWith("0") }) error("Invalid verse key in range: $range")
        val (fromSura, fromAyah, toSura, toAyah) = parts.map(String::toInt)
        check(exists(fromSura, fromAyah) && exists(toSura, toAyah)) { "Verse range names a missing verse: $range" }
        check(index(fromSura, fromAyah) <= index(toSura, toAyah)) { "Reversed verse range: $range" }
        return "$fromSura:$fromAyah-$toSura:$toAyah"
    }
}

/** `getBookmarkGroupByMushafIdAndType`. Ayah bookmarks share one group whatever the mushaf. */
internal fun bookmarkGroup(mushafId: Int?, type: String): String = when (type) {
    "ayah" -> "verses_6236"
    "page" -> when (mushafId) {
        7 -> "pages_548"
        6 -> "pages_610"
        else -> "pages_604"
    }
    else -> "default"
}

/** `getRepresentativeMushafIdForGroup`, used by the reading bookmark DTO. */
internal fun representativeMushafId(group: String): Int = when (group) {
    "pages_548" -> 7
    "pages_610" -> 6
    else -> 5
}

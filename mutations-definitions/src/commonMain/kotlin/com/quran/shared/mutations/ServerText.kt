package com.quran.shared.mutations

/** JavaScript's `String.prototype.trim` set, which the server trims; Kotlin's `trim()` differs. */
private val SERVER_TRIM_CHARACTERS: Set<Char> = buildSet {
    addAll(listOf('\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680'))
    addAll('\u2000'..'\u200A')
    addAll(listOf('\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF'))
}

fun String.trimLikeServer(): String = trim { it in SERVER_TRIM_CHARACTERS }

/** A collection name's identity on the server, which compares names trimmed and lowercased. */
fun collectionNameKey(name: String): String = name.trimLikeServer().lowercase()

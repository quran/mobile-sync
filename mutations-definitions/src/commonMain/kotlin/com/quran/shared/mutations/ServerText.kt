package com.quran.shared.mutations

/**
 * JavaScript's whitespace (WhiteSpace and LineTerminator), which the server trims; Kotlin's `trim()` and `\s` differ,
 * and `\s` differs between platforms.
 */
private val SERVER_WHITESPACE: Set<Char> = buildSet {
    addAll(listOf('\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680'))
    addAll('\u2000'..'\u200A')
    addAll(listOf('\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF'))
}

fun String.trimLikeServer(): String = trim { it in SERVER_WHITESPACE }

/**
 * Trimmed like the server ([trimLikeServer]), with each run of that whitespace inside (spaces, tabs, newlines, the
 * byte order mark, ...) made one space, the same on every platform. For comparing texts only; the server keeps the
 * whitespace inside.
 */
fun String.collapseServerWhitespace(): String = buildString {
    var space = false
    for (char in this@collapseServerWhitespace) {
        if (char in SERVER_WHITESPACE) {
            space = isNotEmpty()
        } else {
            if (space) append(' ')
            space = false
            append(char)
        }
    }
}

/** A collection name's identity on the server, which compares names trimmed and lowercased. */
fun collectionNameKey(name: String): String = name.trimLikeServer().lowercase()

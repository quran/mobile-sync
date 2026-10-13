package com.quran.shared.mutations

/** Mirrors the server's sanitize-html text escaping (`&`, `<` and `>` only), so it stores exactly what this device sent. */
fun String.escapeServerText(): String = buildString(length) {
    for (character in this@escapeServerText) {
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(character)
        }
    }
}

/** Reverses [escapeServerText] in a single pass, so `&amp;lt;` becomes `&lt;` rather than `<`. */
fun String.unescapeServerText(): String = SERVER_TEXT_ENTITY.replace(this) { match ->
    when (match.groupValues[1]) {
        "amp" -> "&"
        "lt" -> "<"
        else -> ">"
    }
}

private val SERVER_TEXT_ENTITY = Regex("&(amp|lt|gt);")

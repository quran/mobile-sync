package com.quran.shared.mutations

/** The server measures text trimmed, then HTML-escaped, in UTF-16 code units. */
enum class ServerTextLimit(val minLength: Int, val maxLength: Int) {
    NOTE_BODY(minLength = 6, maxLength = 10_000),
    COLLECTION_NAME(minLength = 1, maxLength = 255);

    fun serverLength(text: String): Int = text.trimLikeServer().escapeServerText().length

    fun accepts(text: String): Boolean = serverLength(text) in minLength..maxLength
}

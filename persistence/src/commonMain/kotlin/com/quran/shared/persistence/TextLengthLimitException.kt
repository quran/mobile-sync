package com.quran.shared.persistence

import com.quran.shared.mutations.ServerTextLimit

enum class TextLengthField {
    NOTE_BODY,
    COLLECTION_NAME
}

enum class TextLengthBound {
    TOO_SHORT,
    TOO_LONG
}

/**
 * Thrown when a note body or collection name is outside the server's length limits.
 *
 * @property limit the minimum for [TextLengthBound.TOO_SHORT], or the maximum for [TextLengthBound.TOO_LONG].
 * @property length the length as the server measures it: trimmed, HTML-escaped, in UTF-16 code units.
 */
class TextLengthLimitException(
    val field: TextLengthField,
    val bound: TextLengthBound,
    val limit: Int,
    val length: Int
) : IllegalArgumentException(
    "${field.name} length $length is ${if (bound == TextLengthBound.TOO_SHORT) "below the minimum" else "above the maximum"} $limit."
)

internal fun requireServerTextLength(field: TextLengthField, text: String) {
    val serverLimit = when (field) {
        TextLengthField.NOTE_BODY -> ServerTextLimit.NOTE_BODY
        TextLengthField.COLLECTION_NAME -> ServerTextLimit.COLLECTION_NAME
    }
    val length = serverLimit.serverLength(text)
    when {
        length < serverLimit.minLength ->
            throw TextLengthLimitException(field, TextLengthBound.TOO_SHORT, serverLimit.minLength, length)
        length > serverLimit.maxLength ->
            throw TextLengthLimitException(field, TextLengthBound.TOO_LONG, serverLimit.maxLength, length)
    }
}

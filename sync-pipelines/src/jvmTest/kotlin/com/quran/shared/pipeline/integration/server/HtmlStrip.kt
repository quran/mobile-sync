package com.quran.shared.pipeline.integration.server

/**
 * The backend's Joi `htmlStrip()` rule: sanitize-html with `allowedTags: []` and
 * `allowedAttributes: {}`, then `trim()`. Returns `null` when nothing is left, which the rule
 * rejects as "must not contain html tags".
 *
 * sanitize-html parses with htmlparser2 (`decodeEntities: true`) and, in its default `discard`
 * mode, drops every tag but keeps the text between tags. The text of `script`, `style`,
 * `textarea`, `option` and `noscript` is dropped too. Comments, doctypes and processing
 * instructions are dropped. A `<` that does not start a tag stays text. An unterminated tag at the
 * end is dropped. Text is entity-decoded and then re-escaped as `&amp;`, `&lt;` and `&gt;` only, so
 * quotes stay as typed and already-escaped input is unchanged.
 */
internal fun htmlStrip(value: String): String? {
    val out = StringBuilder()
    var i = 0
    var skipUntilClosing: String? = null
    while (i < value.length) {
        val c = value[i]
        if (c != '<') {
            val next = value.indexOf('<', i).let { if (it < 0) value.length else it }
            if (skipUntilClosing == null) out.append(escapeText(decodeEntities(value.substring(i, next))))
            i = next
            continue
        }
        val tag = readMarkup(value, i)
        if (tag == null) {
            // Not markup: a literal '<'.
            if (skipUntilClosing == null) out.append("&lt;")
            i += 1
            continue
        }
        i = tag.end
        val name = tag.name ?: continue
        if (skipUntilClosing != null) {
            if (tag.closing && name == skipUntilClosing) skipUntilClosing = null
        } else if (!tag.closing && name in NON_TEXT_TAGS) {
            skipUntilClosing = name
        }
    }
    return out.toString().jsTrim().ifEmpty { null }
}

/** JavaScript's `String.prototype.trim`: WhiteSpace and LineTerminator code points. */
internal fun String.jsTrim(): String = trim { it in JS_WHITESPACE || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

private val JS_WHITESPACE = setOf('\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿')

private val NON_TEXT_TAGS = setOf("script", "style", "textarea", "option", "noscript")

private class Markup(val end: Int, val name: String?, val closing: Boolean)

/** Reads the markup starting at [start] (a '<'), or returns null when it is literal text. */
private fun readMarkup(s: String, start: Int): Markup? {
    val next = s.getOrNull(start + 1) ?: return null
    return when {
        s.startsWith("<!--", start) -> {
            val close = s.indexOf("-->", start + 4)
            Markup(if (close < 0) s.length else close + 3, name = null, closing = false)
        }
        next == '!' || next == '?' -> Markup(endOfTag(s, start + 2), name = null, closing = false)
        next == '/' -> {
            val nameStart = start + 2
            if (s.getOrNull(nameStart)?.isLetter() != true) {
                // "</" not followed by a letter is a bogus comment up to '>' in htmlparser2.
                return if (s.getOrNull(nameStart) == null) null else Markup(endOfTag(s, nameStart), null, false)
            }
            Markup(endOfTag(s, nameStart), tagName(s, nameStart), closing = true)
        }
        next.isLetter() -> Markup(endOfTag(s, start + 1), tagName(s, start + 1), closing = false)
        else -> null
    }
}

private fun tagName(s: String, from: Int): String {
    var end = from
    while (end < s.length && !s[end].isWhitespace() && s[end] != '/' && s[end] != '>') end++
    return s.substring(from, end).lowercase()
}

/** The index after the '>' closing a tag, honouring quoted attribute values; the end of input when unterminated. */
private fun endOfTag(s: String, from: Int): Int {
    var quote: Char? = null
    var i = from
    while (i < s.length) {
        val c = s[i]
        when {
            quote != null -> if (c == quote) quote = null
            c == '"' || c == '\'' -> quote = c
            c == '>' -> return i + 1
        }
        i++
    }
    return s.length
}

private fun escapeText(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " "
)

private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);?")

/** Decodes numeric entities and the common named ones; unknown names stay as typed. */
private fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
    val body = match.groupValues[1]
    when {
        body.startsWith("#x") || body.startsWith("#X") ->
            body.drop(2).toIntOrNull(16)?.let(::codePoint) ?: match.value
        body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::codePoint) ?: match.value
        else -> NAMED_ENTITIES[body.lowercase()]?.takeIf { match.value.endsWith(";") || body in LEGACY_NO_SEMICOLON }
            ?: match.value
    }
}

private fun codePoint(value: Int): String? = runCatching { String(Character.toChars(value)) }.getOrNull()

/** Named entities htmlparser2 also decodes without a trailing ';' (legacy HTML). */
private val LEGACY_NO_SEMICOLON = setOf("amp", "lt", "gt", "quot", "nbsp")

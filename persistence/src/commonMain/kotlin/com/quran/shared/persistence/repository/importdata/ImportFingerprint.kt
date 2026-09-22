package com.quran.shared.persistence.repository.importdata

import com.quran.shared.persistence.input.ImportAyahHighlight
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportNote
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.ImportReadingSession
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.DEFAULT_COLLECTION_NAME
import com.quran.shared.persistence.model.toStorageValue
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
import org.kotlincrypto.hash.sha2.SHA256

internal object ImportFingerprint {
    fun note(note: ImportNote) = hash(Kind.NOTE) {
        string(normalizedNoteBody(note.body))
        long(note.startSura)
        long(note.startAyah)
        long(note.endSura)
        long(note.endAyah)
    }

    fun highlight(highlight: ImportAyahHighlight) = hash(Kind.HIGHLIGHT) {
        long(highlight.sura)
        long(highlight.ayah)
        byte(highlight.color.fingerprintTag())
    }

    fun collectionMembership(
        membership: ImportCollectionAyahBookmark,
        collection: ImportCollection
    ) = hash(Kind.COLLECTION_MEMBERSHIP) {
        string(canonicalCollectionName(collection.name))
        long(membership.sura)
        long(membership.ayah)
    }

    fun readingSession(session: ImportReadingSession) = hash(Kind.READING_SESSION) {
        long(session.sura)
        long(session.ayah)
        long(session.lastUpdated.toEpochMillisecondsFromPlatform())
    }

    fun readingBookmark(bookmark: ImportReadingBookmark) = hash(Kind.READING_BOOKMARK) {
        byte(bookmark.slot.toStorageValue())
        when (bookmark) {
            is ImportReadingBookmark.Ayah -> {
                byte(1)
                long(bookmark.sura)
                long(bookmark.ayah)
            }
            is ImportReadingBookmark.Page -> {
                byte(2)
                long(bookmark.page)
            }
        }
        nullableString(bookmark.name)
    }

    fun collection(collection: ImportCollection) =
        hash(Kind.COLLECTION) { string(canonicalCollectionName(collection.name)) }

    private fun hash(kind: Kind, fields: Encoder.() -> Unit): String {
        val encoder = Encoder()
        encoder.raw(FORMAT_TAG)
        encoder.byte(FORMAT_VERSION)
        encoder.byte(kind.tag)
        encoder.fields()
        val digest = SHA256().digest(encoder.toByteArray())
        return "v1:" + digest.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    private enum class Kind(val tag: Int) {
        NOTE(1), HIGHLIGHT(2), COLLECTION_MEMBERSHIP(3), READING_SESSION(4), READING_BOOKMARK(5), COLLECTION(6)
    }

    private class Encoder {
        private var bytes = ByteArray(INITIAL_CAPACITY)
        private var size = 0

        fun raw(value: ByteArray) {
            ensureCapacity(value.size)
            value.copyInto(bytes, destinationOffset = size)
            size += value.size
        }

        fun byte(value: Int) {
            ensureCapacity(1)
            bytes[size++] = value.toByte()
        }

        fun string(value: String) {
            val encoded = value.encodeToByteArray()
            uint(encoded.size)
            raw(encoded)
        }

        fun nullableString(value: String?) {
            byte(if (value == null) 0 else 1)
            if (value != null) string(value)
        }

        fun long(value: Int) = long(value.toLong())

        fun long(value: Long) {
            for (shift in 56 downTo 0 step 8) byte((value shr shift).toInt())
        }

        private fun uint(value: Int) {
            for (shift in 24 downTo 0 step 8) byte(value ushr shift)
        }

        fun toByteArray() = bytes.copyOf(size)

        private fun ensureCapacity(additionalBytes: Int) {
            val requiredSize = size + additionalBytes
            if (requiredSize > bytes.size) {
                bytes = bytes.copyOf(maxOf(requiredSize, bytes.size * 2))
            }
        }

        private companion object {
            const val INITIAL_CAPACITY = 64
        }
    }

    private fun AyahHighlightColor.fingerprintTag(): Int = when (this) {
        AyahHighlightColor.BLUE -> 1
        AyahHighlightColor.RED -> 2
        AyahHighlightColor.GREEN -> 3
        AyahHighlightColor.YELLOW -> 4
        AyahHighlightColor.PURPLE -> 5
    }

    private val FORMAT_TAG = "quran-mobile-sync-import".encodeToByteArray()
    private const val FORMAT_VERSION = 1
}

internal fun normalizedNoteBody(body: String): String = body.trim().replace(NOTE_WHITESPACE_REGEX, " ")

internal fun canonicalCollectionName(name: String): String =
    if (name.trim().equals(DEFAULT_COLLECTION_NAME, ignoreCase = true)) DEFAULT_COLLECTION_NAME else name

private val NOTE_WHITESPACE_REGEX = Regex("\\s+")

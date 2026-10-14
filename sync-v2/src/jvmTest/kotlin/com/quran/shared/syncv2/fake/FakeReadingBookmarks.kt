package com.quran.shared.syncv2.fake

import com.quran.shared.mutations.trimLikeServer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * READING_BOOKMARK as the backend has it (`resources/reading-bookmark.ts`, `services/readingBookmarks.ts`,
 * `validators/readingBookmarks.ts`): one row per slot (1..3), which is never deleted.
 * - CREATE upserts by slot; UPDATE replaces the slot's state (404 for a missing row, 422 for another slot);
 *   DELETE clears the location and resets the client timestamps.
 * - The location's mushaf is `mushafId`, required for a placed slot; Joi rejects `mushaf` like any unknown key.
 *   A cleared slot must send every location field as null.
 * - Slot 1 is projected onto the legacy BOOKMARK `isReading` flag (`projectLegacySlotOne`): the bookmark at its
 *   location (created outside Favorites if needed) is the only one with `isReading`.
 * - A POST applies only the last READING_BOOKMARK CREATE per slot (`collapseReadingBookmarkCreates`), so its
 *   response can be shorter than the request.
 */
class FakeReadingBookmarks(private val account: FakeAccount) {

    fun validate(data: JsonObject): JsonObject {
        if (data.int("slot") !in 1..3) throw invalid("\"slot\" must be one of [1, 2, 3]")
        val location = data["type"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val allowed = setOf("slot", "name", "metadata", "clientCreatedAt", "clientUpdatedAt") + when (location) {
            null -> LOCATION_KEYS
            "ayah" -> setOf("mushafId", "type", "key", "verseNumber")
            else -> setOf("mushafId", "type", "key")
        }
        (data.keys - allowed).firstOrNull()?.let { throw invalid("\"$it\" is not allowed") }
        if (location == null) {
            LOCATION_KEYS.firstOrNull { data[it] !is JsonNull }?.let { throw invalid("\"$it\" is required") }
        } else {
            if (location !in setOf("ayah", "page", "surah", "juz")) throw invalid("\"type\" must be valid")
            if (data.int("mushafId") !in MUSHAFS) throw invalid("\"mushafId\" must be a known mushaf")
            val key = data.int("key")?.takeIf { it >= 1 } ?: throw invalid("\"key\" is required")
            if (location == "ayah" && !FakeMutations.verseExists(key, data.int("verseNumber") ?: 0)) throw invalid("Invalid ayah or surah")
        }
        if (((data["metadata"] as? JsonObject)?.size ?: 0) > 10) throw invalid("\"metadata\" must have less than or equal to 10 keys")
        val name = data["name"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.trimLikeServer()?.ifEmpty { null }
        if ((name?.length ?: 0) > 255) throw invalid("\"name\" length must be less than or equal to 255 characters long")
        return if ("name" in data) JsonObject(data + ("name" to (name?.let(::JsonPrimitive) ?: JsonNull))) else data
    }

    fun apply(type: String, id: String?, data: JsonObject): String = when (type) {
        "CREATE" -> {
            val slot = data.int("slot")!!
            val existing = account.readingBookmarks.entries.firstOrNull { it.value.slot == slot }
            val rowId = existing?.key ?: account.newId("rb")
            write(rowId, assign(existing?.value ?: FReadingBookmark(slot, null, null, null, null, null, null, null, null), data))
            rowId
        }
        "UPDATE" -> {
            val existing = account.readingBookmarks[id] ?: throw HttpError(404, "NotFoundError", "Reading bookmark not found")
            if (existing.slot != data.int("slot")) throw invalid("Reading bookmark slot is immutable")
            write(id!!, assign(existing, data))
            id
        }
        else -> {
            val existing = account.readingBookmarks[id] ?: throw HttpError(404, "NotFoundError", "Reading bookmark not found")
            write(id!!, existing.copy(
                type = null, key = null, verse = null, group = null,
                metadata = keepSource(existing.metadata, null), createdAt = null, updatedAt = null
            ))
            id
        }
    }

    private fun assign(current: FReadingBookmark, data: JsonObject): FReadingBookmark {
        val type = (data["type"] as? JsonPrimitive)?.contentOrNull
        return current.copy(
            name = if ("name" in data) (data["name"] as? JsonPrimitive)?.contentOrNull else current.name,
            type = type,
            key = data.int("key").takeIf { type != null },
            verse = data.int("verseNumber").takeIf { type == "ayah" },
            group = type?.let { group(it, data.int("mushafId")!!) },
            // Joi defaults a missing `metadata` to null, so every write replaces it (keeping `source`).
            metadata = keepSource(current.metadata, data["metadata"] as? JsonObject),
            createdAt = data.timestamp("clientCreatedAt") ?: current.createdAt,
            updatedAt = data.timestamp("clientUpdatedAt") ?: current.updatedAt
        )
    }

    private fun write(id: String, bookmark: FReadingBookmark) {
        val created = id !in account.readingBookmarks
        account.readingBookmarks[id] = bookmark
        account.log("READING_BOOKMARK", id, if (created) "CREATE" else "UPDATE")
        if (bookmark.slot == 1) projectSlotOne(bookmark)
    }

    private fun projectSlotOne(slotOne: FReadingBookmark) {
        val target = if (slotOne.type == "ayah") {
            account.bookmarks.entries.firstOrNull { it.value.key == slotOne.key && it.value.verse == slotOne.verse }?.key
                ?: account.newId("bm").also { id ->
                    account.bookmarks[id] = FBookmark(slotOne.key!!, slotOne.verse!!, inDefault = false, isReading = false, account.now(), account.now())
                    account.log("BOOKMARK", id, "CREATE")
                }
        } else {
            null
        }
        account.bookmarks.entries.toList().forEach { (id, bookmark) ->
            val reading = id == target
            if (bookmark.isReading != reading) {
                account.bookmarks[id] = bookmark.copy(isReading = reading)
                account.log("BOOKMARK", id, "UPDATE")
            }
        }
    }

    /** `getBookmarkGroupByMushafIdAndType`. */
    private fun group(type: String, mushafId: Int) = when (type) {
        "ayah" -> "verses_6236"
        "page" -> when (mushafId) {
            7 -> "pages_548"
            6 -> "pages_610"
            else -> "pages_604"
        }
        else -> "default"
    }

    /** `replaceMetadataPreservingSource`. */
    private fun keepSource(current: JsonObject?, replacement: JsonObject?): JsonObject? {
        val source = current?.get("source")
        return if (source != null) JsonObject((replacement ?: JsonObject(emptyMap())) + ("source" to source)) else replacement
    }

    private fun invalid(message: String) = HttpError(422, "ValidationError", message)

    companion object {
        /** The backend's `Mushaf` enum. */
        private val MUSHAFS = setOf(1, 2, 3, 4, 5, 6, 7, 11, 19)
        private val LOCATION_KEYS = setOf("mushafId", "type", "group", "key", "verseNumber")

        /** `collapseReadingBookmarkCreates`: the request indices the server applies. */
        fun effectiveIndices(mutations: List<JsonObject>): List<Int> {
            val lastCreateBySlot = mutations.withIndex()
                .filter { (_, it) -> it.str("resource") == "READING_BOOKMARK" && it.str("type") == "CREATE" }
                .associate { (index, it) -> (it["data"] as? JsonObject)?.int("slot") to index }
            return mutations.indices.filter { index ->
                val mutation = mutations[index]
                mutation.str("resource") != "READING_BOOKMARK" || mutation.str("type") != "CREATE" ||
                    lastCreateBySlot[(mutation["data"] as? JsonObject)?.int("slot")] == index
            }
        }
    }
}

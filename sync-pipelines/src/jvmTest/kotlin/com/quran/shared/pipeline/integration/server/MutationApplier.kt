package com.quran.shared.pipeline.integration.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One account's rules, shared by POSTed mutations and the web actor. */
internal class AccountRules(account: FakeAccount, ownerOf: (String) -> String?) {
    val collections = CollectionRules(account, ownerOf)
    val notes = NoteSessionRules(account, collections, ownerOf)
}

/** `postSync/index.ts` `applyMutations`: dispatches each validated mutation to its resource's rules. */
internal class MutationApplier(private val rules: AccountRules, private val clientId: String) {

    /** Applies [mutation] and returns the resource id the POST response reports (empty when there is none). */
    fun apply(mutation: ValidatedMutation): String? {
        val data = mutation.data
        val id = mutation.resourceId
        val times = ClientTimes.of(data)
        return when (mutation.resource to mutation.type) {
            "COLLECTION" to "CREATE" -> rules.collections.createCollection(data!!.text("name")!!, times)
            "COLLECTION" to "UPDATE" -> id.also { rules.collections.renameCollection(id!!, data!!.text("name")!!, times) }
            "COLLECTION" to "DELETE" -> id.also { rules.collections.deleteCollection(id!!) }
            "BOOKMARK" to "CREATE" -> rules.collections.addBookmark(
                location(data!!), times, isReading = (data["isReading"] as? JsonPrimitive)?.booleanOrNull
            )
            "BOOKMARK" to "DELETE" -> id.also { rules.collections.deleteBookmark(id!!) }
            "COLLECTION_BOOKMARK" to "CREATE" ->
                rules.collections.addMembership(data!!.text("collectionId")!!, location(data), times)
            "COLLECTION_BOOKMARK" to "DELETE" -> {
                val bookmarkId = data!!.text("bookmarkId")
                rules.collections.removeMembership(
                    data.text("collectionId")!!, bookmarkId, location = if (bookmarkId == null) location(data) else null
                )
                null
            }
            "NOTE" to "CREATE" -> rules.notes.createNote(
                body = data!!.text("body")!!,
                ranges = (data["ranges"] as JsonArray).map { (it as JsonPrimitive).content },
                source = clientId,
                times = times,
                saveToQR = (data["saveToQR"] as? JsonPrimitive)?.booleanOrNull ?: false
            )
            "NOTE" to "UPDATE" -> id.also { rules.notes.updateNote(id!!, data!!.text("body")!!, times) }
            "NOTE" to "DELETE" -> id.also { rules.notes.deleteNote(id!!) }
            "READING_SESSION" to "CREATE" -> rules.notes.addSession(data!!.int("chapterNumber"), data.int("verseNumber"), times)
            "READING_SESSION" to "UPDATE" ->
                id.also { rules.notes.moveSession(id!!, data!!.int("chapterNumber"), data.int("verseNumber"), times) }
            "READING_SESSION" to "DELETE" -> id.also { rules.notes.deleteSession(id!!) }
            "READING_BOOKMARK" to "CREATE" -> rules.notes.upsertReadingBookmark(readingBookmarkWrite(data!!), isCreate = true)
            "READING_BOOKMARK" to "UPDATE" -> id.also { rules.notes.replaceReadingBookmark(id!!, readingBookmarkWrite(data!!)) }
            "READING_BOOKMARK" to "DELETE" -> id.also { rules.notes.clearReadingBookmark(id!!) }
            else -> throw validationError("Invalid mutation type: \"${mutation.type}\"")
        }
    }

    /**
     * BOOKMARK and COLLECTION_BOOKMARK read `data.mushaf`, so a body with only `mushafId` gets the
     * default group. READING_BOOKMARK reads `data.mushafId`.
     */
    private fun location(data: JsonObject, mushafKey: String = "mushaf") = BookmarkLocation(
        type = data.text("type") ?: "ayah",
        key = data.int("key"),
        verse = (data["verseNumber"] as? JsonPrimitive)?.intOrNull,
        mushafId = (data[mushafKey] as? JsonPrimitive)?.intOrNull
    )

    private fun readingBookmarkWrite(data: JsonObject) = ReadingBookmarkWrite(
        slot = data.int("slot"),
        location = data.text("type")?.let { location(data, mushafKey = "mushafId") },
        name = if ("name" in data) ReadingBookmarkWrite.Name((data["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull) else null,
        metadata = data["metadata"] as? JsonObject,
        times = ClientTimes.of(data)
    )

    private fun JsonObject.int(key: String): Int = (this[key] as JsonPrimitive).intOrNull!!

    companion object {
        /**
         * `collapseReadingBookmarkCreates`: only the last READING_BOOKMARK CREATE per slot is applied,
         * so the response can be shorter than the request.
         */
        fun collapseReadingBookmarkCreates(mutations: List<ValidatedMutation>): List<ValidatedMutation> {
            val lastBySlot = mutations.filter { it.resource == "READING_BOOKMARK" && it.type == "CREATE" }
                .associate { (it.data!!["slot"] as JsonPrimitive).intOrNull to it.index }
            return mutations.filter {
                it.resource != "READING_BOOKMARK" || it.type != "CREATE" ||
                    lastBySlot[(it.data!!["slot"] as JsonPrimitive).intOrNull] == it.index
            }
        }
    }
}

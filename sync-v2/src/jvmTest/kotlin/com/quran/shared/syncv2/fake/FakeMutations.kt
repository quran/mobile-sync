package com.quran.shared.syncv2.fake

import com.quran.shared.mutations.trimLikeServer
import com.quran.shared.syncv2.fake.FakeAccount.Companion.DEFAULT_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The server's resource rules for one POSTed mutation, mirroring quran.com-users-backend `modules/v1/sync/resources`
 * and the services they call. As on the server, [validate] (the `validateMutationBody` middleware: structure and Joi)
 * runs for the whole batch before the head check, and [apply] runs inside the transaction after it.
 */
class FakeMutations(private val account: FakeAccount, private val legacyFavorites: Boolean = false) {

    /** Structural and Joi checks (422). Returns the data as Joi leaves it: text sanitized and trimmed. */
    fun validate(resource: String, type: String, resourceId: String?, data: JsonObject?): JsonObject {
        val allowed = ALLOWED_TYPES[resource] ?: throw invalid("Invalid resource: \"$resource\"")
        if (resource == "COLLECTION" && resourceId == DEFAULT_ID) throw invalid("The default collection cannot be mutated directly.")
        if (type !in allowed) throw invalid("Invalid mutation type: \"$type\"")
        val keyed = resource != "COLLECTION_BOOKMARK"
        if (type != "CREATE" && keyed && resourceId.isNullOrEmpty()) throw invalid("Missing resourceId")
        if (type == "DELETE" && keyed) return JsonObject(emptyMap())
        val body = data ?: throw invalid("Missing data")
        return when (resource) {
            "COLLECTION" -> {
                allowKeys(body, "name", "clientCreatedAt", "clientUpdatedAt")
                JsonObject(body + ("name" to JsonPrimitive(validName(body))))
            }
            "BOOKMARK" -> body.also {
                allowKeys(it, "key", "type", "verseNumber", "mushaf", "mushafId", "isReading", "clientCreatedAt", "clientUpdatedAt")
                verseOf(it)
            }
            "COLLECTION_BOOKMARK" -> body.also {
                if (it.str("collectionId") == null) throw invalid("\"collectionId\" is required")
                if (type == "DELETE" && it.str("bookmarkId") != null) {
                    allowKeys(it, "collectionId", "bookmarkId")
                } else {
                    allowKeys(it, "collectionId", "key", "type", "verseNumber", "mushaf", "mushafId", "clientCreatedAt", "clientUpdatedAt")
                    verseOf(it)
                }
            }
            "READING_BOOKMARK" -> FakeReadingBookmarks(account).validate(body)
            "NOTE" -> {
                if (type == "CREATE") {
                    allowKeys(body, "body", "ranges", "saveToQR", "clientCreatedAt", "clientUpdatedAt")
                    val ranges = (body["ranges"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                    if (ranges.isNullOrEmpty() || ranges.any { !RANGE.matches(it) }) throw invalid("\"ranges\" is required")
                } else {
                    allowKeys(body, "body", "clientCreatedAt", "clientUpdatedAt")
                }
                JsonObject(body + ("body" to JsonPrimitive(validBody(body))))
            }
            else -> body.also {
                allowKeys(it, "chapterNumber", "verseNumber", "clientCreatedAt", "clientUpdatedAt")
                sessionVerse(it)
            }
        }
    }

    /** Joi: `htmlStrip().min(6).max(10000)`. */
    private fun validBody(data: JsonObject): String {
        val body = sanitizeHtml(data.str("body") ?: throw invalid("\"body\" is required"))
        if (body.length !in 6..10_000) throw invalid("\"body\" length must be 6..10000")
        return body
    }

    /** Applies validated data; returns the resource id the POST response reports. */
    fun apply(resource: String, type: String, resourceId: String?, data: JsonObject): String? = when (resource) {
        "COLLECTION" -> collection(type, resourceId, data)
        "BOOKMARK" -> bookmark(type, resourceId, data)
        "COLLECTION_BOOKMARK" -> membership(type, data)
        "NOTE" -> note(type, resourceId, data)
        "READING_BOOKMARK" -> FakeReadingBookmarks(account).apply(type, resourceId, data)
        else -> session(type, resourceId, data)
    }

    // region NOTE (resources/note.ts): no natural key, so a CREATE always makes a new note; ranges are immutable.
    private fun note(type: String, id: String?, data: JsonObject): String? = when (type) {
        "CREATE" -> {
            val newId = account.newId("note")
            val createdAt = data.timestamp("clientCreatedAt") ?: account.now()
            val ranges = (data["ranges"] as JsonArray).map { (it as JsonPrimitive).content }
            // rangesToIndices throws a plain Error for a verse that doesn't exist or a reversed range: a 500.
            if (ranges.any { !validRange(it) }) throw HttpError(500, "ServerError", "Something went wrong")
            account.notes[newId] = FNote(data.str("body")!!, ranges, createdAt, data.timestamp("clientUpdatedAt") ?: createdAt)
            account.log("NOTE", newId, "CREATE")
            newId
        }
        "UPDATE" -> {
            val existing = account.notes[id] ?: throw HttpError(404, "NotFoundError", "Note not found")
            account.notes[id!!] = existing.copy(body = data.str("body")!!, updatedAt = data.timestamp("clientUpdatedAt") ?: existing.updatedAt)
            account.log("NOTE", id, "UPDATE")
            id
        }
        else -> {
            account.notes.remove(id) ?: throw HttpError(404, "NotFoundError", "Note not found")
            account.log("NOTE", id!!, "DELETE")
            id
        }
    }
    private fun validRange(range: String): Boolean {
        val (startSura, startAyah, endSura, endAyah) = RANGE.matchEntire(range)!!.destructured.toList().map { it.toInt() }
        return verseExists(startSura, startAyah) && verseExists(endSura, endAyah) &&
            (startSura < endSura || (startSura == endSura && startAyah <= endAyah))
    }
    // endregion

    // region COLLECTION (services/collections.ts, collectionName.ts, collectionNameProtection.ts)
    private fun collection(type: String, id: String?, data: JsonObject): String? = when (type) {
        "CREATE" -> {
            val name = data.str("name")!!
            requireUniqueName(name, exceptId = null)
            val newId = account.newId("col")
            val createdAt = data.timestamp("clientCreatedAt") ?: account.now()
            account.collections[newId] = FCollection(name, null, createdAt, data.timestamp("clientUpdatedAt") ?: createdAt)
            account.log("COLLECTION", newId, "CREATE")
            newId
        }
        "UPDATE" -> {
            val existing = account.collections[id] ?: throw HttpError(404, "NotFoundError", "Collection not found")
            val name = data.str("name")!!
            if (name != existing.name) {
                if (FakeAccount.isSystemName(existing.name)) throw HttpError(403, "ForbiddenError", "This system collection cannot be renamed.")
                if (FakeAccount.isSystemName(name)) throw HttpError(403, "ForbiddenError", "System collection names are reserved.")
                if (isProtectedColor(existing)) throw HttpError(403, "ForbiddenError", "This mobile color collection cannot be renamed.")
            }
            requireUniqueName(name, exceptId = id)
            account.collections[id!!] = existing.copy(name = name, updatedAt = data.timestamp("clientUpdatedAt") ?: existing.updatedAt)
            account.log("COLLECTION", id, "UPDATE")
            id
        }
        else -> {
            val existing = account.collections[id] ?: throw HttpError(404, "NotFoundError", "collection is not found")
            if (FakeAccount.isSystemName(existing.name) || isProtectedColor(existing)) {
                throw HttpError(403, "ForbiddenError", "This collection cannot be deleted.")
            }
            account.memberships.keys.filter { it.startsWith("$id-") }.forEach { removeMembership(it) }
            account.collections.remove(id)
            account.log("COLLECTION", id!!, "DELETE")
            id
        }
    }

    /** Joi: `htmlStrip()` (sanitize-html with no allowed tags, then trim; empty is an error) and `max(255)`. */
    private fun validName(data: JsonObject): String {
        val raw = data.str("name") ?: throw invalid("\"name\" is required")
        val name = sanitizeHtml(raw)
        if (name.isEmpty()) throw invalid("\"name\" must not contain html tags")
        if (name.length > 255) throw invalid("\"name\" length must be less than or equal to 255 characters long")
        return name
    }

    private fun requireUniqueName(name: String, exceptId: String?) {
        val key = name.trim().lowercase()
        if (account.collections.any { (id, it) -> id != exceptId && it.name.trim().lowercase() == key }) {
            throw HttpError(409, "UniqueViolationError", "You already have a collection with this name", mapOf("name" to "DUPLICATE"))
        }
    }

    private fun isProtectedColor(collection: FCollection) =
        collection.source in setOf("QDC_ANDROID", "QDC_IOS") && collection.name.trim().lowercase() in FakeAccount.COLORS
    // endregion

    // region BOOKMARK (bookmarks/addBookmark, resources/bookmark.ts)
    private fun bookmark(type: String, id: String?, data: JsonObject): String? = when (type) {
        "CREATE" -> {
            val (key, verse) = verseOf(data)
            val existing = findBookmark(key, verse)
            if (existing != null) {
                // An existing bookmark is only touched: isInDefaultCollection does not change.
                account.bookmarks[existing] = account.bookmarks.getValue(existing).copy(updatedAt = account.now())
                account.log("BOOKMARK", existing, "UPDATE")
                existing
            } else {
                // A new bookmark goes into Favorites by default.
                createBookmark(key, verse, inDefault = true, data)
            }
        }
        else -> {
            val existing = account.bookmarks[id]
            if (existing != null) {
                // The BOOKMARK delete cascades to every membership.
                account.memberships.keys.filter { it.endsWith("-$id") }.forEach { removeMembership(it) }
                account.bookmarks.remove(id)
                account.log("BOOKMARK", id!!, "DELETE")
                if (existing.inDefault) logFavorites(id, "DELETE")
            }
            id
        }
    }

    private fun createBookmark(key: Int, verse: Int, inDefault: Boolean, data: JsonObject): String {
        val id = account.newId("bm")
        val createdAt = data.timestamp("clientCreatedAt") ?: account.now()
        account.bookmarks[id] = FBookmark(key, verse, inDefault, false, createdAt, data.timestamp("clientUpdatedAt") ?: createdAt)
        account.log("BOOKMARK", id, "CREATE")
        if (inDefault) logFavorites(id, "CREATE")
        return id
    }

    /**
     * `ResourceLogSubscriber`'s `__default__` membership log for a bookmark entering or leaving Favorites. A
     * [legacyFavorites] server writes none, as the backend before quran.com-users-backend#1491 did.
     */
    private fun logFavorites(bookmarkId: String, kind: String) {
        if (!legacyFavorites) account.log("COLLECTION_BOOKMARK", "$DEFAULT_ID-$bookmarkId", kind)
    }

    private fun findBookmark(key: Int, verse: Int) =
        account.bookmarks.entries.firstOrNull { it.value.key == key && it.value.verse == verse }?.key
    // endregion

    // region COLLECTION_BOOKMARK (resources/collection-bookmark.ts)
    private fun membership(type: String, data: JsonObject): String? {
        val collectionId = data.str("collectionId")!!
        return when (type) {
            "CREATE" -> {
                val (key, verse) = verseOf(data)
                val bookmarkId = findBookmark(key, verse)
                if (collectionId == DEFAULT_ID) {
                    if (bookmarkId == null) return "$DEFAULT_ID-${createBookmark(key, verse, inDefault = true, data)}"
                    val bookmark = account.bookmarks.getValue(bookmarkId)
                    if (!bookmark.inDefault) {
                        account.bookmarks[bookmarkId] = bookmark.copy(inDefault = true)
                        account.log("BOOKMARK", bookmarkId, "UPDATE")
                        logFavorites(bookmarkId, "CREATE")
                    }
                    return "$DEFAULT_ID-$bookmarkId"
                }
                val collection = account.collections[collectionId] ?: throw HttpError(404, "NotFoundError", "Collection not found")
                val id = bookmarkId ?: createBookmark(key, verse, inDefault = false, data)
                val membershipId = "$collectionId-$id"
                val createdAt = data.timestamp("clientCreatedAt") ?: account.now()
                val updatedAt = data.timestamp("clientUpdatedAt") ?: createdAt
                val existing = account.memberships[membershipId]
                if (existing == null) {
                    account.memberships[membershipId] = FMembership(collectionId, id, createdAt, updatedAt)
                    account.log("COLLECTION_BOOKMARK", membershipId, "CREATE")
                } else {
                    account.memberships[membershipId] = existing.copy(updatedAt = updatedAt)
                }
                // Adding a verse bumps the collection's updatedAt, so GET reports a COLLECTION UPDATE.
                account.collections[collectionId] = collection
                account.log("COLLECTION", collectionId, "UPDATE")
                membershipId
            }
            else -> {
                val bookmarkId = data.str("bookmarkId") ?: verseOf(data).let { (key, verse) -> findBookmark(key, verse) }
                    ?: throw HttpError(404, "NotFoundError", "bookmark is not found")
                if (collectionId == DEFAULT_ID) {
                    val bookmark = account.bookmarks[bookmarkId] ?: throw HttpError(404, "NotFoundError", "bookmark is not found")
                    if (!bookmark.inDefault) throw HttpError(404, "NotFoundError", "collection bookmark is not found")
                    val inCollections = account.memberships.values.count { it.bookmarkId == bookmarkId }
                    logFavorites(bookmarkId, "DELETE")
                    if (inCollections == 0 && !bookmark.isReading) {
                        // Removing from Favorites when no other collection holds it deletes the bookmark.
                        account.bookmarks.remove(bookmarkId)
                        account.log("BOOKMARK", bookmarkId, "DELETE")
                    } else {
                        account.bookmarks[bookmarkId] = bookmark.copy(inDefault = false)
                        account.log("BOOKMARK", bookmarkId, "UPDATE")
                    }
                    return null
                }
                if (collectionId !in account.collections) throw HttpError(404, "NotFoundError", "Collection not found")
                val membershipId = "$collectionId-$bookmarkId"
                if (membershipId !in account.memberships) throw HttpError(404, "NotFoundError", "collection bookmark is not found")
                removeMembership(membershipId)
                null
            }
        }
    }

    private fun removeMembership(id: String) {
        account.memberships.remove(id)
        account.log("COLLECTION_BOOKMARK", id, "DELETE")
    }
    // endregion

    // region READING_SESSION (resources/reading-session.ts)
    private fun session(type: String, id: String?, data: JsonObject): String? = when (type) {
        "CREATE" -> {
            val (chapter, verse) = sessionVerse(data)
            val existing = account.sessions.entries.firstOrNull { it.value.chapter == chapter && it.value.verse == verse }
            if (existing != null) {
                // CREATE upserts by verse and returns the existing id.
                account.sessions[existing.key] = existing.value.copy(
                    createdAt = data.timestamp("clientCreatedAt") ?: existing.value.createdAt,
                    updatedAt = data.timestamp("clientUpdatedAt") ?: existing.value.updatedAt
                )
                account.log("READING_SESSION", existing.key, "UPDATE")
                existing.key
            } else {
                val newId = account.newId("rs")
                val createdAt = data.timestamp("clientCreatedAt") ?: account.now()
                account.sessions[newId] = FSession(chapter, verse, createdAt, data.timestamp("clientUpdatedAt") ?: createdAt)
                account.log("READING_SESSION", newId, "CREATE")
                newId
            }
        }
        "UPDATE" -> {
            val existing = account.sessions[id] ?: throw HttpError(404, "NotFoundError", "Reading session not found")
            val (chapter, verse) = sessionVerse(data)
            // A move deletes whichever session was already at the target verse.
            account.sessions.entries.firstOrNull { it.key != id && it.value.chapter == chapter && it.value.verse == verse }
                ?.let { account.sessions.remove(it.key); account.log("READING_SESSION", it.key, "DELETE") }
            account.sessions[id!!] = existing.copy(
                chapter = chapter, verse = verse,
                updatedAt = data.timestamp("clientUpdatedAt") ?: existing.updatedAt
            )
            account.log("READING_SESSION", id, "UPDATE")
            id
        }
        else -> {
            account.sessions.remove(id) ?: throw HttpError(404, "NotFoundError", "Reading session not found")
            account.log("READING_SESSION", id!!, "DELETE")
            id
        }
    }

    private fun sessionVerse(data: JsonObject): Pair<Int, Int> {
        val chapter = data.int("chapterNumber")?.takeIf { it >= 1 } ?: throw invalid("\"chapterNumber\" is required")
        val verse = data.int("verseNumber")?.takeIf { it >= 1 } ?: throw invalid("\"verseNumber\" is required")
        return chapter to verse
    }
    // endregion

    private fun verseOf(data: JsonObject): Pair<Int, Int> {
        val key = data.int("key") ?: throw invalid("\"key\" is required")
        val verse = data.int("verseNumber") ?: throw invalid("\"verseNumber\" is required")
        if (data.str("mushaf") == null && data.str("mushafId") == null) throw invalid("\"mushafId\" is required")
        if (!verseExists(key, verse)) throw invalid("Invalid ayah or surah")
        return key to verse
    }

    /** Joi rejects unknown keys. */
    private fun allowKeys(data: JsonObject, vararg allowed: String) {
        (data.keys - allowed.toSet()).firstOrNull()?.let { throw invalid("\"$it\" is not allowed") }
    }

    private fun invalid(message: String) = HttpError(422, "ValidationError", message)

    companion object {
        fun verseExists(sura: Int, ayah: Int) = sura in 1..114 && ayah in 1..VERSE_COUNTS[sura - 1]

        private val ALLOWED_TYPES = mapOf(
            "COLLECTION" to setOf("CREATE", "UPDATE", "DELETE"),
            "BOOKMARK" to setOf("CREATE", "DELETE"),
            "COLLECTION_BOOKMARK" to setOf("CREATE", "DELETE"),
            "READING_SESSION" to setOf("CREATE", "UPDATE", "DELETE"),
            "NOTE" to setOf("CREATE", "UPDATE", "DELETE"),
            "READING_BOOKMARK" to setOf("CREATE", "UPDATE", "DELETE")
        )
        private val RANGE = Regex("^(\\d+):(\\d+)-(\\d+):(\\d+)$")

        private val NON_TEXT_TAGS = Regex("<(script|style|textarea|option)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val COMMENTS = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val TAGS = Regex("</?[A-Za-z][^>]*>")

        /**
         * sanitize-html with `allowedTags: []` as Joi's `htmlStrip()` calls it: tags are dropped (script and style with
         * their text), entities are decoded, then the text is escaped (`&`, `<`, `>`) and trimmed.
         */
        fun sanitizeHtml(text: String): String = text
            .replace(NON_TEXT_TAGS, "").replace(COMMENTS, "").replace(TAGS, "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .trimLikeServer()

        private val VERSE_COUNTS = intArrayOf(
            7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110, 98, 135,
            112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53,
            89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12,
            12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25, 22, 17, 19, 26,
            30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6
        )
    }
}

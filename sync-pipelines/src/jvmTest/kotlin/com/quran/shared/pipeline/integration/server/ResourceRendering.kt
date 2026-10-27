package com.quran.shared.pipeline.integration.server

import com.quran.shared.pipeline.integration.server.FakeAccount.Companion.DEFAULT_COLLECTION_ID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What `GET /v1/sync` reports, mirroring `getSync/index.ts`: one collapsed entry per `ResourceLog`
 * row with the *stored* entity (sanitized and trimmed), never the request that wrote it.
 */
internal object ResourceRendering {
    /** The virtual Favorites entry: page 1 only, timestamped with `user.createdAt`. */
    fun defaultCollection(account: FakeAccount): JsonObject = buildJsonObject {
        put("resource", "COLLECTION")
        put("resourceId", DEFAULT_COLLECTION_ID)
        put("type", "CREATE")
        put("data", buildJsonObject {
            put("name", "Favorites")
            put("slug", JsonNull)
            put("isPrivate", false)
            put("isDefault", true)
            put("isSystem", true)
        })
        put("timestamp", account.createdAt)
    }

    fun entry(account: FakeAccount, row: LogRow, vanished: Boolean): JsonObject = buildJsonObject {
        put("resource", row.resource)
        // COLLECTION_BOOKMARK has no resourceId in GET; its identity is in data.
        if (row.resource != "COLLECTION_BOOKMARK") put("resourceId", row.resourceId)
        if (row.deletedAt != null) {
            put("type", "DELETE")
            put("data", if (row.resource == "COLLECTION_BOOKMARK") membershipKey(row.resourceId) else JsonObject(emptyMap()))
            put("timestamp", row.deletedAt)
            return@buildJsonObject
        }
        val isUpdate = row.updatedAt != null && row.updatedAt > row.createdAt
        put("type", if (isUpdate) "UPDATE" else "CREATE")
        put("data", when {
            !vanished -> data(account, row)
            // `hasResourceId` is false for memberships, so the backend always fills the split key.
            row.resource == "COLLECTION_BOOKMARK" -> membershipKey(row.resourceId)
            else -> JsonObject(emptyMap())
        })
        put("timestamp", if (isUpdate) row.updatedAt else row.createdAt)
    }

    /** A live log row whose entity is gone is sent with `{}` (or the split membership key). */
    private fun data(account: FakeAccount, row: LogRow): JsonObject = when (row.resource) {
        "COLLECTION" -> account.collections[row.resourceId]?.let(::collection)
        "BOOKMARK" -> account.bookmarks[row.resourceId]?.let(::bookmark)
        "COLLECTION_BOOKMARK" -> membership(account, row.resourceId)
        "NOTE" -> account.notes[row.resourceId]?.let(::note)
        "READING_SESSION" -> account.sessions[row.resourceId]?.let(::session)
        "READING_BOOKMARK" -> account.readingBookmarks[row.resourceId]?.let { readingBookmark(account, it) }
        else -> null
    } ?: JsonObject(emptyMap())

    fun collection(it: ServerCollection) = buildJsonObject {
        put("name", it.name)
        put("slug", CollectionRules.slug(it.name))
        put("isPrivate", it.isPrivate)
        put("metadata", buildJsonObject { it.source?.let { source -> put("source", source) } })
        put("clientCreatedAt", isoMillis(it.clientCreatedAt))
        put("clientUpdatedAt", isoMillis(it.clientUpdatedAt))
        put("isSystem", CollectionRules.isSystemName(it.name))
    }

    fun bookmark(it: ServerBookmark) = buildJsonObject {
        put("bookmarkType", it.type)
        put("bookmarkGroup", it.group)
        put("key", it.key)
        it.verse?.let { verse -> put("verseNumber", verse) }
        put("isInDefaultCollection", it.inDefault)
        put("isReading", it.isReading)
        put("metadata", JsonObject(emptyMap()))
        put("clientCreatedAt", isoMillis(it.clientCreatedAt))
        put("clientUpdatedAt", isoMillis(it.clientUpdatedAt))
    }

    /** `__default__-<bookmarkId>` reads the bookmark; other ids read the membership row. */
    private fun membership(account: FakeAccount, resourceId: String): JsonObject {
        val key = membershipKey(resourceId)
        val collectionId = (key["collectionId"] as JsonPrimitive).content
        val bookmarkId = (key["bookmarkId"] as JsonPrimitive).content
        val times = if (collectionId == DEFAULT_COLLECTION_ID) {
            account.bookmarks[bookmarkId]?.let { it.clientCreatedAt to it.clientUpdatedAt }
        } else {
            account.memberships[resourceId]?.let { it.clientCreatedAt to it.clientUpdatedAt }
        } ?: return key
        return JsonObject(key + mapOf(
            "clientCreatedAt" to JsonPrimitive(isoMillis(times.first)),
            "clientUpdatedAt" to JsonPrimitive(isoMillis(times.second))
        ))
    }

    /** `splitCollectionBookmarkResourceId`: the first two '-' separated parts. */
    fun membershipKey(resourceId: String): JsonObject {
        val parts = resourceId.split("-")
        return buildJsonObject {
            put("collectionId", parts[0])
            put("bookmarkId", parts.getOrNull(1))
        }
    }

    fun note(it: ServerNote) = buildJsonObject {
        put("body", it.body)
        put("ranges", JsonArray(it.ranges.map(::JsonPrimitive)))
        put("source", it.source)
        put("saveToQR", it.saveToQR)
        put("archivedAt", it.archivedAt?.let(::isoMillis))
        put("attachedEntities", JsonArray(it.attachedEntities))
        put("clientCreatedAt", isoMillis(it.clientCreatedAt))
        put("clientUpdatedAt", isoMillis(it.clientUpdatedAt))
    }

    fun session(it: ServerSession) = buildJsonObject {
        put("chapterNumber", it.chapter)
        put("verseNumber", it.verse)
        put("clientCreatedAt", isoMillis(it.clientCreatedAt))
        put("clientUpdatedAt", isoMillis(it.clientUpdatedAt))
    }

    /**
     * `readingBookmarkDTO`: the representative mushaf for the stored group (the written one with
     * [FakeAccount.keepsMushafId]), and nullable client timestamps.
     */
    fun readingBookmark(account: FakeAccount, it: ServerReadingBookmark) = buildJsonObject {
        put("slot", it.slot)
        put("name", it.name)
        put("mushafId", if (account.keepsMushafId) it.mushafId else it.group?.let(::representativeMushafId))
        put("type", it.type)
        put("group", it.group)
        put("key", it.key)
        put("verseNumber", it.verse)
        put("metadata", it.metadata ?: JsonNull as JsonElement)
        put("clientCreatedAt", it.clientCreatedAt?.let(::isoMillis))
        put("clientUpdatedAt", it.clientUpdatedAt?.let(::isoMillis))
    }
}

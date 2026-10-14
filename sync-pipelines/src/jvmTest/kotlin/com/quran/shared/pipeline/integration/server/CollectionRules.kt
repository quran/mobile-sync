package com.quran.shared.pipeline.integration.server

import com.quran.shared.pipeline.integration.server.FakeAccount.Companion.DEFAULT_COLLECTION_ID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** Where an ayah, page, juz or surah bookmark points. */
data class BookmarkLocation(val type: String, val key: Int, val verse: Int?, val mushafId: Int? = 1) {
    val group: String get() = bookmarkGroup(mushafId, type)

    companion object {
        fun ayah(sura: Int, ayah: Int, mushafId: Int = 1) = BookmarkLocation("ayah", sura, ayah, mushafId)
    }
}

/** Client timestamps a write provides; null means "not provided". */
data class ClientTimes(val createdAt: Long? = null, val updatedAt: Long? = null) {
    companion object {
        fun of(data: JsonObject?) = ClientTimes(data?.millis("clientCreatedAt"), data?.millis("clientUpdatedAt"))
    }
}

/**
 * Collection, bookmark and membership rules, mirroring `sync/resources/{collection,bookmark,
 * collection-bookmark}.ts` and the services they call (`collections.ts`, `collectionName.ts`,
 * `collectionNameProtection.ts`, `addBookmark`), plus the `ResourceLogSubscriber` side effects.
 *
 * [ownerOf] names the account that owns an id, so a foreign id gets 403 instead of 404.
 */
internal class CollectionRules(
    private val account: FakeAccount,
    private val ownerOf: (String) -> String?
) {
    // region COLLECTION
    fun createCollection(name: String, times: ClientTimes, requestSource: String? = null, source: String? = null): String {
        val normalized = name.jsTrim()
        if (requestSource in QDC_SOURCES && normalized.lowercase(Locale.ROOT) in PROTECTED_COLORS &&
            account.collections.values.any { it.source in QDC_SOURCES && it.name.jsTrim().lowercase(Locale.ROOT) == normalized.lowercase(Locale.ROOT) }
        ) {
            throw HttpError(409, "UniqueViolationError", "A collection with this protected color name already exists.")
        }
        requireNameAvailable(normalized, exceptId = null)
        val id = account.newId("col")
        val createdAt = times.createdAt ?: account.now()
        account.collections[id] = ServerCollection(id, normalized, source = source, clientCreatedAt = createdAt, clientUpdatedAt = times.updatedAt ?: createdAt)
        account.log("COLLECTION", id, LogKind.CREATE)
        return id
    }

    fun renameCollection(id: String, name: String, times: ClientTimes) {
        val existing = account.collections[id] ?: throw missing(id, "Collection not found")
        val normalized = name.jsTrim()
        if (name != existing.name) {
            if (isSystemName(existing.name)) throw forbidden("This system collection cannot be renamed.")
            if (isSystemName(name)) throw forbidden("System collection names are reserved.")
            if (isProtectedMobileColor(existing)) throw forbidden("This mobile color collection cannot be renamed.")
        }
        if (normalized.lowercase(Locale.ROOT) in PROTECTED_COLORS &&
            account.collections.values.any { it.id != id && it.name.jsTrim().lowercase(Locale.ROOT) == normalized.lowercase(Locale.ROOT) }
        ) {
            throw HttpError(409, "UniqueViolationError", "A collection with this protected color name already exists.")
        }
        requireNameAvailable(normalized, exceptId = id)
        update(existing, existing.copy(
            name = normalized,
            clientCreatedAt = times.createdAt ?: existing.clientCreatedAt,
            clientUpdatedAt = times.updatedAt ?: existing.clientUpdatedAt
        ))
    }

    fun deleteCollection(id: String) {
        val existing = account.collections[id] ?: throw missing(id, "collection is not found")
        if (isSystemName(existing.name)) throw forbidden("This system collection cannot be deleted.")
        if (isProtectedMobileColor(existing)) throw forbidden("This mobile color collection cannot be deleted.")
        // Memberships are removed through the ORM, so each one logs a COLLECTION_BOOKMARK DELETE.
        account.memberships.values.filter { it.collectionId == id }.forEach(::removeMembership)
        account.collections.remove(id)
        account.log("COLLECTION", id, LogKind.DELETE)
    }

    /** Bumps `updatedAt`, as adding a verse to the collection does. */
    private fun touchCollection(id: String) = account.log("COLLECTION", id, LogKind.UPDATE)

    private fun update(old: ServerCollection, new: ServerCollection) {
        if (old == new) return
        account.collections[new.id] = new
        account.log("COLLECTION", new.id, LogKind.UPDATE)
    }

    /** Unique per user on `lower(btrim(name, <unicode whitespace>))`. */
    private fun requireNameAvailable(name: String, exceptId: String?) {
        val key = nameKey(name)
        if (account.collections.values.any { it.id != exceptId && nameKey(it.name) == key }) {
            throw HttpError(
                409, "UniqueViolationError", "You already have a collection with this name",
                buildJsonObject { put("name", "DUPLICATE") }
            )
        }
    }

    private fun isProtectedMobileColor(collection: ServerCollection) =
        collection.source in QDC_SOURCES && collection.name.jsTrim().lowercase(Locale.ROOT) in PROTECTED_COLORS
    // endregion

    // region BOOKMARK
    /** `addBookmark`: upserts by (type, group, key, verse). A new bookmark goes into Favorites. */
    fun addBookmark(location: BookmarkLocation, times: ClientTimes, isReading: Boolean? = null): String {
        val existing = findBookmark(location)
        if (existing != null) {
            // `bookmark.createdAt = new Date()` always makes it an UPDATE.
            val touched = existing.copy(
                clientCreatedAt = times.createdAt ?: existing.clientCreatedAt,
                clientUpdatedAt = times.updatedAt ?: existing.clientUpdatedAt
            )
            setBookmark(existing, touched, touch = true)
            if (isReading == true) projectReading(touched.id)
            return existing.id
        }
        val createdAt = times.createdAt ?: account.now()
        val bookmark = ServerBookmark(
            id = account.newId("bm"), type = location.type, group = location.group, key = location.key,
            verse = location.verse, inDefault = isReading != true, isReading = if (isReading == false) false else null,
            clientCreatedAt = createdAt, clientUpdatedAt = times.updatedAt ?: createdAt
        )
        setBookmark(null, bookmark)
        if (isReading == true) projectReading(bookmark.id)
        return bookmark.id
    }

    /** BOOKMARK DELETE: a missing bookmark is a no-op; otherwise its memberships go first. */
    fun deleteBookmark(id: String) {
        val bookmark = account.bookmarks[id] ?: if (ownerOf(id) != null) throw forbidden() else return
        account.memberships.values.filter { it.bookmarkId == id }.forEach(::removeMembership)
        removeBookmark(bookmark)
    }

    fun findBookmark(location: BookmarkLocation): ServerBookmark? = account.bookmarks.values.firstOrNull {
        it.type == location.type && it.group == location.group && it.key == location.key &&
            (location.verse == null || it.verse == location.verse)
    }

    /** Writes a bookmark and logs it, including the virtual Favorites membership when `isInDefaultCollection` changes. */
    fun setBookmark(old: ServerBookmark?, new: ServerBookmark, touch: Boolean = false) {
        account.bookmarks[new.id] = new
        when {
            old == null -> {
                account.log("BOOKMARK", new.id, LogKind.CREATE)
                if (new.inDefault) ensureDefaultMembership(new.id)
            }
            old != new || touch -> {
                account.log("BOOKMARK", new.id, LogKind.UPDATE)
                if (old.inDefault != new.inDefault) {
                    if (new.inDefault) ensureDefaultMembership(new.id) else account.log("COLLECTION_BOOKMARK", defaultId(new.id), LogKind.DELETE)
                }
            }
        }
    }

    private fun removeBookmark(bookmark: ServerBookmark) {
        account.bookmarks.remove(bookmark.id)
        account.log("BOOKMARK", bookmark.id, LogKind.DELETE)
        if (bookmark.inDefault) account.log("COLLECTION_BOOKMARK", defaultId(bookmark.id), LogKind.DELETE)
    }

    /** A live, never-updated default membership row is left alone; otherwise it is (re)created. */
    private fun ensureDefaultMembership(bookmarkId: String) {
        val row = account.logRow("COLLECTION_BOOKMARK", defaultId(bookmarkId))
        if (row != null && row.updatedAt == null && row.deletedAt == null) return
        account.log("COLLECTION_BOOKMARK", defaultId(bookmarkId), LogKind.CREATE)
    }

    /** `projectLegacySlotOne`: exactly one bookmark carries `isReading=true`, the slot 1 location. */
    fun projectReading(bookmarkId: String?) {
        account.bookmarks.values.filter { it.isReading == true && it.id != bookmarkId }.forEach {
            setBookmark(it, it.copy(isReading = null))
        }
        bookmarkId?.let { id -> account.bookmarks[id]?.let { setBookmark(it, it.copy(isReading = true)) } }
    }
    // endregion

    // region COLLECTION_BOOKMARK
    fun addMembership(collectionId: String, location: BookmarkLocation, times: ClientTimes): String {
        if (collectionId == DEFAULT_COLLECTION_ID) {
            val existing = findBookmark(location)
            if (existing == null) {
                val createdAt = times.createdAt ?: account.now()
                val bookmark = ServerBookmark(
                    account.newId("bm"), location.type, location.group, location.key, location.verse, inDefault = true,
                    clientCreatedAt = createdAt, clientUpdatedAt = times.updatedAt ?: createdAt
                )
                setBookmark(null, bookmark)
                return defaultId(bookmark.id)
            }
            setBookmark(existing, existing.copy(
                inDefault = true,
                clientCreatedAt = times.createdAt ?: existing.clientCreatedAt,
                clientUpdatedAt = times.updatedAt ?: existing.clientUpdatedAt
            ))
            return defaultId(existing.id)
        }
        // The collection must exist and belong to the user: a same-batch reference to a new collection still works,
        // but another user's collection is a 404 here, not a 403.
        if (collectionId !in account.collections) throw notFound("Collection not found")
        val bookmark = findBookmark(location) ?: run {
            val createdAt = times.createdAt ?: account.now()
            ServerBookmark(
                account.newId("bm"), location.type, location.group, location.key, location.verse, inDefault = false,
                clientCreatedAt = createdAt, clientUpdatedAt = times.updatedAt ?: createdAt
            ).also { setBookmark(null, it) }
        }
        val membershipId = "$collectionId-${bookmark.id}"
        val existing = account.memberships[membershipId]
        if (existing == null) {
            val createdAt = times.createdAt ?: account.now()
            account.memberships[membershipId] = ServerMembership(collectionId, bookmark.id, createdAt, times.updatedAt ?: createdAt)
            account.log("COLLECTION_BOOKMARK", membershipId, LogKind.CREATE)
        } else {
            val updated = existing.copy(
                clientCreatedAt = times.createdAt ?: existing.clientCreatedAt,
                clientUpdatedAt = times.updatedAt ?: existing.clientUpdatedAt
            )
            if (updated != existing) {
                account.memberships[membershipId] = updated
                account.log("COLLECTION_BOOKMARK", membershipId, LogKind.UPDATE)
            }
        }
        touchCollection(collectionId)
        return membershipId
    }

    /**
     * The sync DELETE. Removing from Favorites deletes a bookmark that is in no other collection and
     * is not a reading bookmark. Removing from another collection keeps an orphaned bookmark;
     * [deleteOrphan] gives the web's REST behavior, which deletes it.
     */
    fun removeMembership(collectionId: String, bookmarkId: String?, location: BookmarkLocation?, deleteOrphan: Boolean = false) {
        if (collectionId == DEFAULT_COLLECTION_ID) {
            val bookmark = (bookmarkId?.let { account.bookmarks[it] } ?: location?.let(::findBookmark))
                ?: throw notFound("bookmark is not found")
            if (!bookmark.inDefault) throw notFound("collection bookmark is not found")
            if (account.memberships.values.none { it.bookmarkId == bookmark.id } && bookmark.isReading != true) {
                removeBookmark(bookmark)
            } else {
                setBookmark(bookmark, bookmark.copy(inDefault = false))
            }
            return
        }
        if (collectionId !in account.collections) throw missing(collectionId, "Collection not found")
        val bookmark = if (bookmarkId != null) {
            account.bookmarks[bookmarkId] ?: throw missing(bookmarkId, "bookmark is not found")
        } else {
            location?.let(::findBookmark) ?: throw notFound("bookmark is not found")
        }
        val membership = account.memberships["$collectionId-${bookmark.id}"]
            ?: throw notFound("collection bookmark is not found")
        removeMembership(membership)
        if (deleteOrphan && account.memberships.values.none { it.bookmarkId == bookmark.id } &&
            !bookmark.inDefault && bookmark.isReading != true
        ) {
            removeBookmark(bookmark)
        }
    }

    private fun removeMembership(membership: ServerMembership) {
        account.memberships.remove(membership.id)
        account.log("COLLECTION_BOOKMARK", membership.id, LogKind.DELETE)
    }
    // endregion

    private fun missing(id: String, message: String): HttpError =
        if (ownerOf(id).let { it != null && it != account.name }) forbidden() else notFound(message)

    companion object {
        val PROTECTED_COLORS = setOf("blue", "pink", "green", "yellow", "purple")
        private val QDC_SOURCES = setOf("QDC_IOS", "QDC_ANDROID")
        private const val TRIM_CHARACTERS =
            "\t\n\u000B\u000C\r              " +
                "    　﻿"

        fun defaultId(bookmarkId: String) = "$DEFAULT_COLLECTION_ID-$bookmarkId"

        /** `isSystemCollectionName`: `system:highlights:<color>`, compared trimmed and lowercased. */
        fun isSystemName(name: String): Boolean {
            val key = name.trim().lowercase(Locale.ROOT)
            return key.startsWith("system:highlights:") && key.removePrefix("system:highlights:") in PROTECTED_COLORS
        }

        /** The unique index key, `lower(btrim(name, <set>))`. Postgres `lower()` is approximated with the root locale. */
        fun nameKey(name: String): String = name.trim { it in TRIM_CHARACTERS }.lowercase(Locale.ROOT)

        fun slug(name: String): String = name.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), "-")
    }
}

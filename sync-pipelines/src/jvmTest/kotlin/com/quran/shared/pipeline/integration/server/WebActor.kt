package com.quran.shared.pipeline.integration.server

import com.quran.shared.pipeline.integration.server.FakeAccount.Companion.DEFAULT_COLLECTION_ID
import kotlinx.serialization.json.JsonObject

/**
 * The website, writing straight into [account]'s server data with the backend's REST semantics.
 * It needs no sync head, so it can write between a device's GET and POST. Text goes through the
 * same `htmlStrip()` rule as the REST validators.
 */
class WebActor internal constructor(private val server: FakeSyncServer, val account: String) {
    private fun <T> write(block: AccountRules.(FakeAccount) -> T): T = synchronized(server.lock) {
        val account = server.account(account)
        server.rulesFor(account).block(account)
    }

    private fun text(value: String, field: String): String = htmlStrip(value) ?: throw validationError("\"$field\" must not contain html tags")

    // region Bookmarks and collections
    /** `POST /bookmarks`: a new bookmark goes into Favorites. Returns the bookmark id. */
    fun addBookmark(sura: Int, ayah: Int, mushafId: Int = 1, times: ClientTimes = ClientTimes()): String =
        write { collections.addBookmark(BookmarkLocation.ayah(sura, ayah, mushafId), times) }

    fun addPageBookmark(page: Int, mushafId: Int = 1): String =
        write { collections.addBookmark(BookmarkLocation("page", page, null, mushafId), ClientTimes()) }

    fun addSurahBookmark(sura: Int): String =
        write { collections.addBookmark(BookmarkLocation("surah", sura, null), ClientTimes()) }

    /** Adds an ayah to a collection; `__default__` is Favorites. Returns the membership id. */
    fun addToCollection(collectionId: String, sura: Int, ayah: Int, mushafId: Int = 1, times: ClientTimes = ClientTimes()): String =
        write { collections.addMembership(collectionId, BookmarkLocation.ayah(sura, ayah, mushafId), times) }

    fun addToFavorites(sura: Int, ayah: Int): String = addToCollection(DEFAULT_COLLECTION_ID, sura, ayah)

    /** Removing from Favorites deletes a bookmark that is in no other collection. */
    fun removeFromFavorites(sura: Int, ayah: Int) =
        write { collections.removeMembership(DEFAULT_COLLECTION_ID, null, BookmarkLocation.ayah(sura, ayah)) }

    /** REST `deleteCollectionBookmark`, which, unlike sync, deletes an orphaned bookmark not in Favorites. */
    fun removeFromCollection(collectionId: String, sura: Int, ayah: Int) = write {
        collections.removeMembership(collectionId, null, BookmarkLocation.ayah(sura, ayah), deleteOrphan = true)
    }

    fun createCollection(name: String, times: ClientTimes = ClientTimes()): String =
        write { collections.createCollection(text(name, "name"), times) }

    /** A color collection an older QDC app created, which the backend protects from rename and delete. */
    fun createLegacyColorCollection(name: String, source: String = "QDC_IOS"): String =
        write { collections.createCollection(text(name, "name"), ClientTimes(), requestSource = source, source = source) }

    fun renameCollection(id: String, name: String, times: ClientTimes = ClientTimes()) =
        write { collections.renameCollection(id, text(name, "name"), times) }

    fun deleteCollection(id: String) = write { collections.deleteCollection(id) }

    /** Highlights an ayah the way the apps store it: membership in `system:highlights:<color>`. */
    fun highlight(sura: Int, ayah: Int, color: String): String {
        val name = "system:highlights:$color"
        val id = collectionId(name) ?: createCollection(name)
        addToCollection(id, sura, ayah)
        return id
    }

    fun collectionId(name: String): String? =
        write { account -> account.collections.values.firstOrNull { it.name == name }?.id }
    // endregion

    // region Notes
    /** Creates a note; [ranges] like "2:255-2:257". Returns its id. */
    fun createNote(
        body: String,
        vararg ranges: String,
        attachedEntities: List<JsonObject> = emptyList(),
        times: ClientTimes = ClientTimes()
    ): String = write {
        notes.createNote(noteBody(body), ranges.toList(), source = "web", times = times, attachedEntities = attachedEntities)
    }

    fun updateNote(id: String, body: String, times: ClientTimes = ClientTimes()) = write { notes.updateNote(id, noteBody(body), times) }

    fun deleteNote(id: String) = write { notes.deleteNote(id) }

    /** Archives a note on QuranReflect (`archivedAt`). */
    fun archiveNote(id: String) = write { account -> notes.archiveNote(id, account.now()) }

    fun noteId(body: String): String? = write { account -> account.notes.values.firstOrNull { it.body == body }?.id }

    private fun noteBody(body: String): String = text(body, "body").also {
        if (it.length !in 6..10_000) throw validationError("\"body\" length must be between 6 and 10000 characters")
    }
    // endregion

    // region Reading bookmarks and sessions
    fun setAyahReadingBookmark(slot: Int, sura: Int, ayah: Int, mushafId: Int = 1, times: ClientTimes = ClientTimes()): String =
        write { notes.upsertReadingBookmark(ReadingBookmarkWrite(slot, BookmarkLocation.ayah(sura, ayah, mushafId), times = times)) }

    fun setPageReadingBookmark(slot: Int, page: Int, mushafId: Int = 1, times: ClientTimes = ClientTimes()): String = write {
        requireReadingPage(page, mushafId)
        notes.upsertReadingBookmark(ReadingBookmarkWrite(slot, BookmarkLocation("page", page, null, mushafId), times = times))
    }

    fun renameReadingBookmark(slot: Int, name: String?): String = write { account ->
        val existing = account.readingBookmarks.values.firstOrNull { it.slot == slot }
        val normalized = name?.jsTrim()?.ifEmpty { null }
        if (existing == null) {
            notes.upsertReadingBookmark(ReadingBookmarkWrite(slot, null, ReadingBookmarkWrite.Name(normalized)))
        } else {
            val mushafId = if (account.keepsMushafId) existing.mushafId else existing.group?.let(::representativeMushafId)
            val location = existing.type?.let { BookmarkLocation(it, existing.key!!, existing.verse, mushafId) }
            notes.replaceReadingBookmark(existing.id, ReadingBookmarkWrite(slot, location, ReadingBookmarkWrite.Name(normalized), existing.metadata))
            existing.id
        }
    }

    /** `clearReadingBookmarkBySlot`: unplaces the slot (creating it if needed) and keeps its name. */
    fun clearReadingBookmark(slot: Int): String = write { account ->
        val id = account.readingBookmarks.values.firstOrNull { it.slot == slot }?.id
            ?: notes.upsertReadingBookmark(ReadingBookmarkWrite(slot, null))
        notes.clearReadingBookmark(id)
        id
    }

    fun addReadingSession(sura: Int, ayah: Int, times: ClientTimes = ClientTimes()): String =
        write { notes.addSession(sura, ayah, times) }

    fun moveReadingSession(id: String, sura: Int, ayah: Int, times: ClientTimes = ClientTimes()) =
        write { notes.moveSession(id, sura, ayah, times) }

    fun deleteReadingSession(id: String) = write { notes.deleteSession(id) }

    fun sessionId(sura: Int, ayah: Int): String? =
        write { account -> account.sessions.values.firstOrNull { it.chapter == sura && it.verse == ayah }?.id }
    // endregion
}

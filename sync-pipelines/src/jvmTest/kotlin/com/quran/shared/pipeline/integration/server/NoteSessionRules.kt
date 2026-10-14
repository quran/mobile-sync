package com.quran.shared.pipeline.integration.server

import kotlinx.serialization.json.JsonObject

/** A reading bookmark write: a placed location, or none for an unplaced (cleared) slot. */
data class ReadingBookmarkWrite(
    val slot: Int,
    val location: BookmarkLocation?,
    /** Absent leaves the stored name; present (including null) replaces it. */
    val name: Name? = null,
    val metadata: JsonObject? = null,
    val times: ClientTimes = ClientTimes()
) {
    data class Name(val value: String?)
}

/**
 * Note, reading session and reading bookmark rules, mirroring `sync/resources/{note,
 * reading-session,reading-bookmark}.ts` and `services/readingBookmarks.ts`.
 */
internal class NoteSessionRules(
    private val account: FakeAccount,
    private val collections: CollectionRules,
    private val ownerOf: (String) -> String?
) {
    // region NOTE
    fun createNote(
        body: String,
        ranges: List<String>,
        source: String,
        times: ClientTimes,
        saveToQR: Boolean = false,
        attachedEntities: List<JsonObject> = emptyList()
    ): String {
        val stored = ranges.map(Verses::normalizeRange)
        val id = account.newId("note")
        val createdAt = times.createdAt ?: account.now()
        account.notes[id] = ServerNote(
            id, body, stored, source, saveToQR, attachedEntities = attachedEntities,
            clientCreatedAt = createdAt, clientUpdatedAt = times.updatedAt ?: createdAt
        )
        account.log("NOTE", id, LogKind.CREATE)
        return id
    }

    /** NOTE UPDATE changes only the body and the client timestamps. */
    fun updateNote(id: String, body: String, times: ClientTimes) {
        val note = account.notes[id] ?: throw missing(id, "Note not found")
        writeNote(note, note.copy(
            body = body,
            clientCreatedAt = times.createdAt ?: note.clientCreatedAt,
            clientUpdatedAt = times.updatedAt ?: note.clientUpdatedAt
        ))
    }

    fun archiveNote(id: String, archivedAt: Long?) {
        val note = account.notes[id] ?: throw missing(id, "Note not found")
        writeNote(note, note.copy(archivedAt = archivedAt))
    }

    fun deleteNote(id: String) {
        account.notes[id] ?: throw missing(id, "Note not found")
        account.notes.remove(id)
        account.log("NOTE", id, LogKind.DELETE)
    }

    private fun writeNote(old: ServerNote, new: ServerNote) {
        if (old == new) return
        account.notes[new.id] = new
        account.log("NOTE", new.id, LogKind.UPDATE)
    }
    // endregion

    // region READING_SESSION
    /** CREATE upserts by verse: it returns the existing id and overwrites the provided client timestamps. */
    fun addSession(chapter: Int, verse: Int, times: ClientTimes): String {
        val existing = account.sessions.values.firstOrNull { it.chapter == chapter && it.verse == verse }
        if (existing != null) {
            account.sessions[existing.id] = existing.copy(
                clientCreatedAt = times.createdAt ?: existing.clientCreatedAt,
                clientUpdatedAt = times.updatedAt ?: existing.clientUpdatedAt
            )
            account.log("READING_SESSION", existing.id, LogKind.UPDATE)
            return existing.id
        }
        val id = account.newId("rs")
        val createdAt = times.createdAt ?: account.now()
        account.sessions[id] = ServerSession(id, chapter, verse, createdAt, times.updatedAt ?: createdAt)
        account.log("READING_SESSION", id, LogKind.CREATE)
        return id
    }

    /** Moving onto a verse that already has a session deletes that session first. */
    fun moveSession(id: String, chapter: Int, verse: Int, times: ClientTimes) {
        val session = account.sessions[id] ?: throw missing(id, "Reading session not found")
        account.sessions.values.firstOrNull { it.id != id && it.chapter == chapter && it.verse == verse }?.let {
            account.sessions.remove(it.id)
            account.log("READING_SESSION", it.id, LogKind.DELETE)
        }
        account.sessions[id] = session.copy(
            chapter = chapter, verse = verse,
            clientCreatedAt = times.createdAt ?: session.clientCreatedAt,
            clientUpdatedAt = times.updatedAt ?: session.clientUpdatedAt
        )
        account.log("READING_SESSION", id, LogKind.UPDATE)
    }

    fun deleteSession(id: String) {
        account.sessions[id] ?: throw missing(id, "Reading session not found")
        account.sessions.remove(id)
        account.log("READING_SESSION", id, LogKind.DELETE)
    }
    // endregion

    // region READING_BOOKMARK
    /** CREATE upserts by slot. */
    fun upsertReadingBookmark(write: ReadingBookmarkWrite, isCreate: Boolean = true): String {
        val existing = account.readingBookmarks.values.firstOrNull { it.slot == write.slot }
        val times = if (isCreate) write.times.withCreateDefaults() else write.times
        val base = existing ?: ServerReadingBookmark(
            id = account.newId("rb"), slot = write.slot, name = write.name?.value, metadata = write.metadata,
            clientCreatedAt = times.createdAt, clientUpdatedAt = times.updatedAt
        )
        return writeReadingBookmark(existing, assign(base, write, times, resetTimes = false))
    }

    /** UPDATE: a missing bookmark gets 404, and a changed slot gets 422. */
    fun replaceReadingBookmark(id: String, write: ReadingBookmarkWrite) {
        val existing = account.readingBookmarks[id] ?: throw missing(id, "Reading bookmark not found")
        if (existing.slot != write.slot) throw validationError("Reading bookmark slot is immutable")
        writeReadingBookmark(existing, assign(existing, write, write.times, resetTimes = false))
    }

    /** DELETE clears the slot: the row stays with its name, so GET reports an UPDATE. */
    fun clearReadingBookmark(id: String) {
        val existing = account.readingBookmarks[id] ?: throw missing(id, "Reading bookmark not found")
        writeReadingBookmark(existing, assign(existing, ReadingBookmarkWrite(existing.slot, null), ClientTimes(), resetTimes = true))
    }

    private fun assign(
        base: ServerReadingBookmark,
        write: ReadingBookmarkWrite,
        times: ClientTimes,
        resetTimes: Boolean
    ): ServerReadingBookmark {
        val location = write.location
        return base.copy(
            type = location?.type, group = location?.group, key = location?.key,
            verse = location?.verse.takeIf { location?.type == "ayah" },
            name = write.name?.let { it.value } ?: base.name,
            metadata = if (write.location == null && resetTimes) base.metadata else write.metadata,
            clientCreatedAt = if (resetTimes) null else times.createdAt ?: base.clientCreatedAt,
            clientUpdatedAt = if (resetTimes) null else times.updatedAt ?: base.clientUpdatedAt
        )
    }

    private fun writeReadingBookmark(old: ServerReadingBookmark?, new: ServerReadingBookmark): String {
        if (old != new) {
            account.readingBookmarks[new.id] = new
            account.log("READING_BOOKMARK", new.id, if (old == null) LogKind.CREATE else LogKind.UPDATE)
        }
        if (new.slot == 1) projectSlotOne(new)
        return new.id
    }

    /** Slot 1 is projected onto a legacy BOOKMARK with `isReading=true`, created outside Favorites if needed. */
    private fun projectSlotOne(slotOne: ServerReadingBookmark) {
        val target = if (slotOne.isPlaced) {
            val location = BookmarkLocation(slotOne.type!!, slotOne.key!!, slotOne.verse, representativeMushafId(slotOne.group!!))
            collections.findBookmark(location) ?: run {
                val now = account.now()
                ServerBookmark(
                    account.newId("bm"), location.type, slotOne.group, location.key, location.verse,
                    inDefault = false, clientCreatedAt = now, clientUpdatedAt = now
                ).also { collections.setBookmark(null, it) }
            }
        } else {
            null
        }
        collections.projectReading(target?.id)
    }
    // endregion

    private fun ClientTimes.withCreateDefaults(): ClientTimes {
        val created = createdAt ?: account.now()
        return ClientTimes(created, updatedAt ?: created)
    }

    private fun missing(id: String, message: String): HttpError =
        if (ownerOf(id).let { it != null && it != account.name }) forbidden() else notFound(message)
}

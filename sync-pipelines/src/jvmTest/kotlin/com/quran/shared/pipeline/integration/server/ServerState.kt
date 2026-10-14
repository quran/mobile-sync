package com.quran.shared.pipeline.integration.server

import com.quran.shared.pipeline.integration.server.FakeAccount.Companion.DEFAULT_COLLECTION_ID

/** A copy of one account's server data and log, for assertions. */
data class ServerState(
    val account: String,
    val head: Long,
    val collections: List<ServerCollection>,
    val bookmarks: List<ServerBookmark>,
    val memberships: List<ServerMembership>,
    val notes: List<ServerNote>,
    val sessions: List<ServerSession>,
    val readingBookmarks: List<ServerReadingBookmark>,
    val log: List<LogRow>
) {
    fun collectionNamed(name: String): ServerCollection? = collections.firstOrNull { it.name == name }

    fun bookmarkAt(sura: Int, ayah: Int): ServerBookmark? =
        bookmarks.firstOrNull { it.type == "ayah" && it.key == sura && it.verse == ayah }

    /** Ayah keys ("sura:ayah") in Favorites. */
    val favorites: Set<String>
        get() = bookmarks.filter { it.inDefault && it.type == "ayah" }.map { "${it.key}:${it.verse}" }.toSet()

    /** Ayah keys ("sura:ayah") in the collection with [collectionId]; `__default__` gives [favorites]. */
    fun versesIn(collectionId: String): Set<String> {
        if (collectionId == DEFAULT_COLLECTION_ID) return favorites
        val byId = bookmarks.associateBy { it.id }
        return memberships.filter { it.collectionId == collectionId }
            .mapNotNull { byId[it.bookmarkId] }
            .filter { it.type == "ayah" }
            .map { "${it.key}:${it.verse}" }
            .toSet()
    }

    /**
     * Fails when the server holds duplicates the backend's unique indexes or the client's natural
     * keys forbid: one collection per name key, one bookmark per location, one membership per pair,
     * one session per verse, one reading bookmark per slot, and no two notes with the same
     * `(clientCreatedAt, ranges)`.
     */
    fun assertNoDuplicates() {
        val problems = buildList {
            collections.groupBy { CollectionRules.nameKey(it.name) }.filterValues { it.size > 1 }
                .forEach { (key, rows) -> add("collections named '$key': ${rows.map { it.id }}") }
            bookmarks.groupBy { listOf(it.type, it.group, it.key, it.verse) }.filterValues { it.size > 1 }
                .forEach { (key, rows) -> add("bookmarks at $key: ${rows.map { it.id }}") }
            memberships.groupBy { it.id }.filterValues { it.size > 1 }
                .forEach { (key, _) -> add("membership $key twice") }
            sessions.groupBy { it.chapter to it.verse }.filterValues { it.size > 1 }
                .forEach { (key, rows) -> add("sessions at ${key.first}:${key.second}: ${rows.map { it.id }}") }
            readingBookmarks.groupBy { it.slot }.filterValues { it.size > 1 }
                .forEach { (slot, rows) -> add("reading bookmarks in slot $slot: ${rows.map { it.id }}") }
            notes.groupBy { it.clientCreatedAt to it.ranges }.filterValues { it.size > 1 }
                .forEach { (key, rows) -> add("notes created at ${key.first} on ${key.second}: ${rows.map { it.id }}") }
        }
        if (problems.isNotEmpty()) throw AssertionError("Server account '$account' has duplicates:\n" + problems.joinToString("\n"))
    }

    companion object {
        internal fun of(account: FakeAccount) = ServerState(
            account = account.name,
            head = account.head,
            collections = account.collections.values.toList(),
            bookmarks = account.bookmarks.values.toList(),
            memberships = account.memberships.values.toList(),
            notes = account.notes.values.toList(),
            sessions = account.sessions.values.toList(),
            readingBookmarks = account.readingBookmarks.values.toList(),
            log = account.logs.values.sortedWith(compareBy<LogRow> { it.lastChangeAt }.thenBy { it.seq })
        )
    }
}

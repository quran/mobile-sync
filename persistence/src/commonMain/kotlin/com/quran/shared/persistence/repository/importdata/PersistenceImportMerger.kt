package com.quran.shared.persistence.repository.importdata

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.ImportAyahHighlight
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.PersistenceImportResult
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.model.DEFAULT_COLLECTION_NAME
import com.quran.shared.persistence.model.DatabaseCollection
import com.quran.shared.persistence.model.highlightColorForCollectionName
import com.quran.shared.persistence.model.isSystemCollectionName
import com.quran.shared.persistence.repository.bookmark.AyahBookmarkResolutionState
import com.quran.shared.persistence.repository.bookmark.AyahBookmarkStore
import com.quran.shared.persistence.repository.bookmark.BookmarkDependencyReconciler
import com.quran.shared.persistence.repository.bookmark.activeSavedCollectionIdsForBookmark
import com.quran.shared.persistence.repository.collection.CollectionStore
import com.quran.shared.persistence.repository.readingbookmark.ReadingBookmarkStore
import com.quran.shared.persistence.repository.readingsession.extension.hasPendingLocalMutation
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

internal class PersistenceImportMerger(
    private val database: QuranDatabase,
    private val reconciler: BookmarkDependencyReconciler,
    private val data: PersistenceImportData,
    private val trackHistory: Boolean,
    private val context: CoroutineContext
) {
    private val ayahBookmarkStore = AyahBookmarkStore(database)
    private val collectionStore = CollectionStore(database)
    private val readingBookmarkStore = ReadingBookmarkStore(database)
    private var bookmarksImported = 0
    private var collectionsImported = 0
    private var collectionBookmarksImported = 0
    private var readingSessionsImported = 0
    private var notesImported = 0
    private var readingBookmarksImported = 0
    private var highlightsImported = 0
    private var readingSessionsUpdated = 0
    private var matched = 0
    private var keptExisting = 0
    private var alreadyProcessed = 0
    private var didChange = false

    fun merge(): PersistenceImportResult {
        val collectionsByImportId = data.collections.associateBy(ImportCollection::importId)
        data.collections.forEach { collection ->
            val name = canonicalCollectionName(collection.name)
            require(!isSystemCollectionName(name) || name == DEFAULT_COLLECTION_NAME) {
                "Collection destination name is reserved."
            }
        }
        data.collectionBookmarks.forEach { membership ->
            require(membership.collectionImportId in collectionsByImportId) {
                "Collection membership references an unknown collection."
            }
        }

        val referencedCollectionIds = data.collectionBookmarks.mapTo(mutableSetOf()) { it.collectionImportId }
        val emptyCollectionCandidates = data.collections.filterNot { it.importId in referencedCollectionIds }
            .deduplicateImportCandidates(
                fingerprint = ImportFingerprint::collection,
                modifiedAt = { it.lastUpdated.toEpochMillisecondsFromPlatform() },
                createdAt = { effectiveCreatedAtMillis(it.createdAt, it.lastUpdated) }
            )
        val readingBookmarkCandidates = data.readingBookmarks.deduplicateImportCandidates(
            fingerprint = ImportFingerprint::readingBookmark,
            modifiedAt = { it.lastUpdated.toEpochMillisecondsFromPlatform() },
            createdAt = { it.lastUpdated.toEpochMillisecondsFromPlatform() }
        )
        val membershipCandidates = data.collectionBookmarks.deduplicateImportCandidates(
            fingerprint = {
                ImportFingerprint.collectionMembership(
                    it,
                    collectionsByImportId.getValue(it.collectionImportId)
                )
            },
            modifiedAt = { it.lastUpdated.toEpochMillisecondsFromPlatform() },
            createdAt = { effectiveCreatedAtMillis(it.createdAt, it.lastUpdated) }
        )
        val sessionCandidates = data.readingSessions.deduplicateImportCandidates(
            fingerprint = ImportFingerprint::readingSession,
            modifiedAt = { it.lastUpdated.toEpochMillisecondsFromPlatform() },
            createdAt = { effectiveCreatedAtMillis(it.createdAt, it.lastUpdated) }
        ).sortedByDescending { it.lastUpdated.toEpochMillisecondsFromPlatform() }
        val noteCandidates = data.notes.deduplicateImportCandidates(
            fingerprint = ImportFingerprint::note,
            modifiedAt = { it.lastUpdated.toEpochMillisecondsFromPlatform() },
            createdAt = { effectiveCreatedAtMillis(it.createdAt, it.lastUpdated) },
            tieBreak = { it.body }
        )
        val highlightCandidates = data.highlights.deduplicateImportCandidates(
            fingerprint = ImportFingerprint::highlight,
            modifiedAt = { it.lastUpdated.toEpochMillisecondsFromPlatform() },
            createdAt = { effectiveCreatedAtMillis(it.createdAt, it.lastUpdated) }
        )

        val history = if (trackHistory) {
            ImportHistoryTracker(
                database = database,
                fingerprints = buildList {
                    emptyCollectionCandidates.mapTo(this, ImportFingerprint::collection)
                    membershipCandidates.mapTo(this) {
                        ImportFingerprint.collectionMembership(
                            it,
                            collectionsByImportId.getValue(it.collectionImportId)
                        )
                    }
                    sessionCandidates.mapTo(this, ImportFingerprint::readingSession)
                    noteCandidates.mapTo(this, ImportFingerprint::note)
                    readingBookmarkCandidates.mapTo(this, ImportFingerprint::readingBookmark)
                    highlightCandidates.mapTo(this, ImportFingerprint::highlight)
                }
            )
        } else {
            null
        }
        val emptyCollections = history?.unseen(emptyCollectionCandidates, ImportFingerprint::collection)
            ?: emptyCollectionCandidates
        val memberships = history?.unseen(membershipCandidates) {
            ImportFingerprint.collectionMembership(
                it,
                collectionsByImportId.getValue(it.collectionImportId)
            )
        } ?: membershipCandidates
        val sessions = history?.unseen(sessionCandidates, ImportFingerprint::readingSession) ?: sessionCandidates
        val notes = history?.unseen(noteCandidates, ImportFingerprint::note) ?: noteCandidates
        val readingBookmarks = history?.unseen(readingBookmarkCandidates, ImportFingerprint::readingBookmark)
            ?: readingBookmarkCandidates
        val highlights = history?.unseen(highlightCandidates, ImportFingerprint::highlight) ?: highlightCandidates
        val candidateCount = emptyCollectionCandidates.size + membershipCandidates.size + sessionCandidates.size +
            noteCandidates.size + readingBookmarkCandidates.size + highlightCandidates.size
        val unseenCount = emptyCollections.size + memberships.size + sessions.size + notes.size +
            readingBookmarks.size + highlights.size
        alreadyProcessed = candidateCount - unseenCount
        val collectionIndex = CollectionIndex(collectionStore.activeCollections())
        val bookmarkIndex = mutableMapOf<Pair<Int, Int>, ResolvedBookmark>()
        val noteKeys = if (notes.isEmpty()) {
            mutableSetOf()
        } else {
            database.notesQueries.getNotes().executeAsList().mapTo(mutableSetOf()) { row ->
                NoteKey(
                    normalizedBody = normalizedNoteBody(row.note),
                    startSura = row.start_sura,
                    startAyah = row.start_ayah,
                    endSura = row.end_sura,
                    endAyah = row.end_ayah
                )
            }
        }
        val highlightColors = mutableMapOf<Pair<Int, Int>, MutableSet<AyahHighlightColor>>()
        if (highlights.isNotEmpty()) {
            database.bookmark_collectionsQueries.getCollectionBookmarksWithDetails().executeAsList().forEach { row ->
                val color = highlightColorForCollectionName(row.collection_name) ?: return@forEach
                highlightColors.getOrPut(row.sura.toInt() to row.ayah.toInt(), ::mutableSetOf).add(color)
            }
        }

        val highlightsByPosition = highlights.groupBy { it.sura to it.ayah }
        val highlightWinners = highlightsByPosition.mapValues { (_, candidates) ->
            candidates.minWith(HIGHLIGHT_PRIORITY)
        }
        val bookmarkDatesByPosition = buildBookmarkDatesByPosition(memberships, highlightWinners.values)
        val firstSaveModifiedAtByPosition = memberships
            .groupBy { it.sura to it.ayah }
            .mapValues { (_, candidates) -> candidates.maxOf { it.lastUpdated.toEpochMillisecondsFromPlatform() } }

        emptyCollections.forEach { collection ->
            context.ensureActive()
            val modifiedAt = collection.lastUpdated.toEpochMillisecondsFromPlatform()
            val resolution = resolveCollection(
                canonicalCollectionName(collection.name),
                effectiveCreatedAtMillis(collection.createdAt, collection.lastUpdated),
                modifiedAt,
                collectionIndex
            )
            if (resolution.inserted) {
                collectionsImported++
                didChange = true
            } else {
                matched++
            }
            history?.record(ImportFingerprint.collection(collection))
        }

        memberships.forEach { membership ->
            context.ensureActive()
            val destination = requireNotNull(collectionsByImportId[membership.collectionImportId])
            val modifiedAt = membership.lastUpdated.toEpochMillisecondsFromPlatform()
            val collection = resolveCollection(
                canonicalCollectionName(destination.name),
                effectiveCreatedAtMillis(destination.createdAt, destination.lastUpdated),
                destination.lastUpdated.toEpochMillisecondsFromPlatform(),
                collectionIndex
            )
            if (collection.inserted) {
                collectionsImported++
                didChange = true
            }
            val bookmarkDates = requireNotNull(bookmarkDatesByPosition[membership.sura to membership.ayah])
            val bookmark = resolveBookmark(membership.sura, membership.ayah, bookmarkDates, bookmarkIndex)
            val existing = database.bookmark_collectionsQueries
                .getCollectionBookmarkFor(bookmark.localId, collection.localId)
                .executeAsOneOrNull()
            if (existing?.is_active == 1L) {
                matched++
            } else {
                // A highlight can create the parent before it becomes an app-facing saved bookmark.
                // Match repository writes by stamping the first saved membership.
                val isFirstSavedMembership = bookmark.preexisting &&
                    database.activeSavedCollectionIdsForBookmark(bookmark.localId).isEmpty()
                database.bookmark_collectionsQueries.insertImportedBookmarkCollection(
                    bookmark_local_id = bookmark.localId,
                    collection_local_id = collection.localId,
                    created_at = effectiveCreatedAtMillis(membership.createdAt, membership.lastUpdated),
                    modified_at = modifiedAt
                )
                if (isFirstSavedMembership) {
                    database.bookmarksQueries.touchBookmarkForFirstSavedMembership(
                        local_id = bookmark.localId,
                        modified_at = firstSaveModifiedAtByPosition.getValue(membership.sura to membership.ayah)
                    )
                }
                if (existing == null) collectionBookmarksImported++
                didChange = true
            }
            history?.record(ImportFingerprint.collectionMembership(membership, destination))
        }

        notes.forEach { note ->
            context.ensureActive()
            val key = NoteKey(
                normalizedBody = normalizedNoteBody(note.body),
                startSura = note.startSura.toLong(),
                startAyah = note.startAyah.toLong(),
                endSura = note.endSura.toLong(),
                endAyah = note.endAyah.toLong()
            )
            if (key in noteKeys) {
                matched++
            } else {
                database.notesQueries.insertImportedNote(
                    note = note.body,
                    start_sura = note.startSura.toLong(),
                    start_ayah = note.startAyah.toLong(),
                    end_sura = note.endSura.toLong(),
                    end_ayah = note.endAyah.toLong(),
                    created_at = effectiveCreatedAtMillis(note.createdAt, note.lastUpdated),
                    modified_at = note.lastUpdated.toEpochMillisecondsFromPlatform()
                )
                noteKeys.add(key)
                notesImported++
                didChange = true
            }
            history?.record(ImportFingerprint.note(note))
        }

        sessions.forEach { session ->
            context.ensureActive()
            val modifiedAt = session.lastUpdated.toEpochMillisecondsFromPlatform()
            val existing = database.reading_sessionsQueries
                .getReadingSessionForChapterVerse(session.sura.toLong(), session.ayah.toLong())
                .executeAsOneOrNull()
            when {
                existing == null -> {
                    database.reading_sessionsQueries.insertImportedReadingSession(
                        chapter_number = session.sura.toLong(),
                        verse_number = session.ayah.toLong(),
                        created_at = effectiveCreatedAtMillis(session.createdAt, session.lastUpdated),
                        modified_at = modifiedAt
                    )
                    readingSessionsImported++
                    didChange = true
                }
                existing.modified_at == modifiedAt -> matched++
                existing.hasPendingLocalMutation() || existing.modified_at > modifiedAt -> {
                    keptExisting++
                }
                else -> {
                    database.reading_sessionsQueries.updateReadingSession(
                        chapter_number = session.sura.toLong(),
                        verse_number = session.ayah.toLong(),
                        modified_at = modifiedAt,
                        local_id = existing.local_id
                    )
                    readingSessionsUpdated++
                    didChange = true
                }
            }
            history?.record(ImportFingerprint.readingSession(session))
        }

        importHighlights(
            highlightsByPosition,
            highlightWinners,
            bookmarkDatesByPosition,
            history,
            context,
            bookmarkIndex,
            highlightColors
        )

        readingBookmarks.forEach { bookmark ->
            context.ensureActive()
            when (bookmark) {
                is ImportReadingBookmark.Ayah -> readingBookmarkStore.setAyah(
                    slot = bookmark.slot,
                    sura = bookmark.sura,
                    ayah = bookmark.ayah,
                    timestampMillis = bookmark.lastUpdated.toEpochMillisecondsFromPlatform()
                )
                is ImportReadingBookmark.Page -> readingBookmarkStore.setPage(
                    slot = bookmark.slot,
                    page = bookmark.page,
                    timestampMillis = bookmark.lastUpdated.toEpochMillisecondsFromPlatform()
                )
            }
            readingBookmarkStore.rename(
                slot = bookmark.slot,
                name = bookmark.name,
                timestampMillis = bookmark.lastUpdated.toEpochMillisecondsFromPlatform()
            )
            readingBookmarksImported++
            didChange = true
            history?.record(ImportFingerprint.readingBookmark(bookmark))
        }

        context.ensureActive()
        if (didChange) {
            reconciler.reconcile()
        }
        return result()
    }

    private fun importHighlights(
        highlightsByPosition: Map<Pair<Int, Int>, List<ImportAyahHighlight>>,
        highlightWinners: Map<Pair<Int, Int>, ImportAyahHighlight>,
        bookmarkDatesByPosition: Map<Pair<Int, Int>, BookmarkDates>,
        history: ImportHistoryTracker?,
        context: CoroutineContext,
        bookmarkIndex: MutableMap<Pair<Int, Int>, ResolvedBookmark>,
        highlightColors: MutableMap<Pair<Int, Int>, MutableSet<AyahHighlightColor>>
    ) {
        highlightsByPosition.forEach { (position, candidates) ->
            context.ensureActive()
            val existingColors = highlightColors[position].orEmpty()
            if (existingColors.isNotEmpty()) {
                candidates.forEach { candidate ->
                    if (candidate.color in existingColors) matched++ else keptExisting++
                    history?.record(ImportFingerprint.highlight(candidate))
                }
                return@forEach
            }

            val winner = highlightWinners.getValue(position)
            val modifiedAt = winner.lastUpdated.toEpochMillisecondsFromPlatform()
            val collectionResolution = collectionStore.getOrCreateActiveHighlight(
                winner.color,
                modifiedAt
            )
            val collection = collectionResolution.collection
            if (collectionResolution.changed) didChange = true
            val bookmark = resolveBookmark(
                winner.sura,
                winner.ayah,
                requireNotNull(bookmarkDatesByPosition[position]),
                bookmarkIndex
            ).localId
            val existingLink = database.bookmark_collectionsQueries
                .getCollectionBookmarkFor(bookmark, collection.local_id)
                .executeAsOneOrNull()
            if (existingLink?.is_active == 1L) {
                matched++
            } else {
                database.bookmark_collectionsQueries.insertImportedBookmarkCollection(
                    bookmark_local_id = bookmark,
                    collection_local_id = collection.local_id,
                    created_at = effectiveCreatedAtMillis(winner.createdAt, winner.lastUpdated),
                    modified_at = modifiedAt
                )
                highlightsImported++
                didChange = true
            }
            history?.record(ImportFingerprint.highlight(winner))
            candidates.filterNot { it === winner }.forEach { candidate ->
                keptExisting++
                history?.record(ImportFingerprint.highlight(candidate))
            }
        }
    }

    private fun resolveCollection(
        name: String,
        createdAt: Long,
        modifiedAt: Long,
        index: CollectionIndex
    ): CollectionResolution {
        if (name == DEFAULT_COLLECTION_NAME) {
            val collection = collectionStore.requireDefault()
            return CollectionResolution(collection.local_id, false)
        }
        index.find(name)?.let { return CollectionResolution(it, false) }
        val inserted = collectionStore.insertImported(name, createdAt, modifiedAt)
        index.add(inserted.name, inserted.local_id)
        return CollectionResolution(inserted.local_id, true)
    }

    private fun resolveBookmark(
        sura: Int,
        ayah: Int,
        bookmarkDates: BookmarkDates,
        index: MutableMap<Pair<Int, Int>, ResolvedBookmark>
    ): ResolvedBookmark {
        val position = sura to ayah
        index[position]?.let { return it }
        val resolution = ayahBookmarkStore.resolve(
            sura,
            ayah,
            bookmarkDates.createdAt,
            bookmarkDates.modifiedAt
        )
        when (resolution.state) {
            AyahBookmarkResolutionState.EXISTING -> Unit
            AyahBookmarkResolutionState.INSERTED -> {
                bookmarksImported++
                didChange = true
            }
            AyahBookmarkResolutionState.REACTIVATED -> didChange = true
        }
        return ResolvedBookmark(
            localId = resolution.bookmark.local_id,
            preexisting = resolution.state == AyahBookmarkResolutionState.EXISTING
        ).also { index[position] = it }
    }

    private fun result() = PersistenceImportResult(
        bookmarksImported = bookmarksImported,
        collectionsImported = collectionsImported,
        collectionBookmarksImported = collectionBookmarksImported,
        readingSessionsImported = readingSessionsImported,
        notesImported = notesImported,
        readingBookmarksImported = readingBookmarksImported,
        highlightsImported = highlightsImported,
        readingSessionsUpdated = readingSessionsUpdated,
        matched = matched,
        keptExisting = keptExisting,
        alreadyProcessed = alreadyProcessed,
        changed = didChange
    )
}

private data class CollectionResolution(val localId: Long, val inserted: Boolean)
private data class BookmarkDates(val createdAt: Long, val modifiedAt: Long)

/** A parent bookmark resolved for this import; [preexisting] is true when it was already active. */
private data class ResolvedBookmark(val localId: Long, val preexisting: Boolean)

private val HIGHLIGHT_PRIORITY = compareByDescending<ImportAyahHighlight> {
    it.lastUpdated.toEpochMillisecondsFromPlatform()
}.thenBy(ImportFingerprint::highlight)

private data class NoteKey(
    val normalizedBody: String,
    val startSura: Long,
    val startAyah: Long,
    val endSura: Long,
    val endAyah: Long
)

private class CollectionIndex(collections: List<DatabaseCollection>) {
    private val exactNames = mutableMapOf<String, Long>()
    private val caseInsensitiveNames = mutableMapOf<String, Long>()

    init {
        collections
            .filter { it.is_system == 0L }
            .sortedBy { it.local_id }
            .forEach { collection -> add(collection.name, collection.local_id) }
    }

    fun find(name: String): Long? = exactNames[name] ?: caseInsensitiveNames[name.collectionNameKey()]

    fun add(name: String, localId: Long) {
        if (name !in exactNames) exactNames[name] = localId
        val normalizedName = name.collectionNameKey()
        if (normalizedName !in caseInsensitiveNames) caseInsensitiveNames[normalizedName] = localId
    }
}

private fun String.collectionNameKey(): String = trim().lowercase()

private fun effectiveCreatedAtMillis(createdAt: PlatformDateTime?, lastUpdated: PlatformDateTime): Long =
    (createdAt ?: lastUpdated).toEpochMillisecondsFromPlatform()

private fun buildBookmarkDatesByPosition(
    memberships: List<ImportCollectionAyahBookmark>,
    highlights: Collection<ImportAyahHighlight>
): Map<Pair<Int, Int>, BookmarkDates> {
    val bookmarkDatesByPosition = mutableMapOf<Pair<Int, Int>, BookmarkDates>()
    memberships.forEach { membership ->
        bookmarkDatesByPosition.include(
            membership.sura,
            membership.ayah,
            effectiveCreatedAtMillis(membership.createdAt, membership.lastUpdated),
            membership.lastUpdated.toEpochMillisecondsFromPlatform()
        )
    }
    highlights.forEach { highlight ->
        bookmarkDatesByPosition.include(
            highlight.sura,
            highlight.ayah,
            effectiveCreatedAtMillis(highlight.createdAt, highlight.lastUpdated),
            highlight.lastUpdated.toEpochMillisecondsFromPlatform()
        )
    }
    return bookmarkDatesByPosition
}

private fun MutableMap<Pair<Int, Int>, BookmarkDates>.include(
    sura: Int,
    ayah: Int,
    createdAt: Long,
    modifiedAt: Long
) {
    val position = sura to ayah
    val existing = this[position]
    this[position] = BookmarkDates(
        createdAt = minOf(existing?.createdAt ?: createdAt, createdAt),
        modifiedAt = maxOf(existing?.modifiedAt ?: modifiedAt, modifiedAt)
    )
}

private fun <T> List<T>.deduplicateImportCandidates(
    fingerprint: (T) -> String,
    modifiedAt: (T) -> Long,
    createdAt: (T) -> Long,
    tieBreak: (T) -> String = { "" }
): List<T> = groupBy(fingerprint).values.map { candidates ->
    candidates.minWith(
        compareByDescending<T>(modifiedAt)
            .thenBy(createdAt)
            .thenBy(tieBreak)
    )
}

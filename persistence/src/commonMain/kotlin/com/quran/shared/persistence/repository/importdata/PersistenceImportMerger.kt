package com.quran.shared.persistence.repository.importdata

import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.input.ImportAyahHighlight
import com.quran.shared.persistence.input.ImportCollection
import com.quran.shared.persistence.input.ImportCollectionAyahBookmark
import com.quran.shared.persistence.input.ImportReadingBookmark
import com.quran.shared.persistence.input.PersistenceImportData
import com.quran.shared.persistence.input.PersistenceImportResult
import com.quran.shared.persistence.model.DEFAULT_COLLECTION_NAME
import com.quran.shared.persistence.model.isSystemCollectionName
import com.quran.shared.persistence.repository.collection.CollectionStore
import com.quran.shared.persistence.repository.note.NoteStore
import com.quran.shared.persistence.repository.readingbookmark.ReadingBookmarkStore
import com.quran.shared.persistence.repository.readingbookmark.mapping.ReadingBookmarkPageMapper
import com.quran.shared.persistence.repository.readingsession.ReadingSessionStore
import com.quran.shared.persistence.util.PlatformDateTime
import com.quran.shared.persistence.util.toEpochMillisecondsFromPlatform
import com.quran.shared.syncv2.db.SyncV2Database
import com.quran.shared.syncv2.types.NoteRange
import com.quran.shared.syncv2.types.ReadingLocation
import com.quran.shared.syncv2.types.noteIdentity
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

internal class PersistenceImportMerger(
    private val database: QuranDatabase,
    synced: SyncV2Database,
    private val data: PersistenceImportData,
    private val trackHistory: Boolean,
    private val context: CoroutineContext
) {
    private val collectionStore = CollectionStore(synced)
    private val noteStore = NoteStore(synced)
    // The nudge for a whole import in one pass: an import can bring thousands of notes, often sharing times.
    private val newNotes by lazy { noteStore.batch() }
    private val sessionStore = ReadingSessionStore(synced)
    private val readingBookmarkStore = ReadingBookmarkStore(synced)
    private val notesQueries = synced.notesQueries
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
        data.collections.forEach { collection ->
            val name = canonicalCollectionName(collection.name)
            require(!isSystemCollectionName(name) || name == DEFAULT_COLLECTION_NAME) {
                "Collection destination name is reserved."
            }
        }
        // Entries sharing a canonical name are one destination; its fingerprint is that name.
        val collectionsByName = data.collections.deduplicateImportCandidates(
            fingerprint = ImportFingerprint::collection,
            modifiedAt = { it.lastUpdated.toEpochMillisecondsFromPlatform() },
            createdAt = { effectiveCreatedAtMillis(it.createdAt, it.lastUpdated) }
        ).associateBy { canonicalCollectionName(it.name) }
        data.collectionBookmarks.forEach { membership ->
            require(canonicalCollectionName(membership.collectionName) in collectionsByName) {
                "Collection membership references an unknown collection."
            }
        }
        fun destinationOf(membership: ImportCollectionAyahBookmark): ImportCollection =
            collectionsByName.getValue(canonicalCollectionName(membership.collectionName))

        val referencedCollectionNames = data.collectionBookmarks.mapTo(mutableSetOf()) {
            canonicalCollectionName(it.collectionName)
        }
        val emptyCollectionCandidates = collectionsByName
            .filterKeys { it !in referencedCollectionNames }
            .values.toList()
        val readingBookmarkCandidates = data.readingBookmarks.deduplicateImportCandidates(
            fingerprint = ImportFingerprint::readingBookmark,
            modifiedAt = { it.lastUpdated.toEpochMillisecondsFromPlatform() },
            createdAt = { it.lastUpdated.toEpochMillisecondsFromPlatform() }
        )
        val membershipCandidates = data.collectionBookmarks.deduplicateImportCandidates(
            fingerprint = { ImportFingerprint.collectionMembership(it, destinationOf(it)) },
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
                    membershipCandidates.mapTo(this) { ImportFingerprint.collectionMembership(it, destinationOf(it)) }
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
            ImportFingerprint.collectionMembership(it, destinationOf(it))
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
        val bookmarkedVerses = mutableSetOf<Pair<Int, Int>>()
        val noteIdentities = if (notes.isEmpty()) {
            mutableSetOf()
        } else {
            noteStore.all().mapTo(mutableSetOf()) { noteIdentity(it.body, it.ranges) }
        }
        val highlightsByPosition = highlights.groupBy { it.sura to it.ayah }

        emptyCollections.forEach { collection ->
            context.ensureActive()
            val resolution = resolveCollection(
                canonicalCollectionName(collection.name),
                effectiveCreatedAtMillis(collection.createdAt, collection.lastUpdated),
                collection.lastUpdated.toEpochMillisecondsFromPlatform()
            )
            if (resolution.inserted) collectionsImported++ else matched++
            history?.record(ImportFingerprint.collection(collection))
        }

        memberships.forEach { membership ->
            context.ensureActive()
            val destination = destinationOf(membership)
            val collection = resolveCollection(
                canonicalCollectionName(destination.name),
                effectiveCreatedAtMillis(destination.createdAt, destination.lastUpdated),
                destination.lastUpdated.toEpochMillisecondsFromPlatform()
            )
            if (collection.inserted) collectionsImported++
            val position = membership.sura to membership.ayah
            if (position !in bookmarkedVerses && collectionStore.membershipsOf(membership.sura, membership.ayah).isEmpty()) {
                bookmarkedVerses += position
                bookmarksImported++
            }
            val added = collectionStore.addVerse(
                collection.localId,
                membership.sura,
                membership.ayah,
                effectiveCreatedAtMillis(membership.createdAt, membership.lastUpdated)
            )
            if (added) {
                collectionBookmarksImported++
                didChange = true
            } else {
                matched++
            }
            history?.record(ImportFingerprint.collectionMembership(membership, destination))
        }

        notes.forEach { note ->
            context.ensureActive()
            val ranges = listOf(NoteRange(note.startSura, note.startAyah, note.endSura, note.endAyah))
            val identity = noteIdentity(note.body, ranges)
            if (identity in noteIdentities) {
                matched++
            } else {
                newNotes.insert(
                    body = note.body,
                    ranges = ranges,
                    createdAt = effectiveCreatedAtMillis(note.createdAt, note.lastUpdated),
                    updatedAt = note.lastUpdated.toEpochMillisecondsFromPlatform()
                )
                noteIdentities.add(identity)
                notesImported++
                didChange = true
            }
            history?.record(ImportFingerprint.note(note))
        }

        sessions.forEach { session ->
            context.ensureActive()
            val modifiedAt = session.lastUpdated.toEpochMillisecondsFromPlatform()
            val existing = sessionStore.at(session.sura, session.ayah)
            when {
                existing == null -> {
                    sessionStore.visit(session.sura, session.ayah, effectiveCreatedAtMillis(session.createdAt, session.lastUpdated), modifiedAt)
                    readingSessionsImported++
                    didChange = true
                }
                existing.updated_at == modifiedAt -> matched++
                // Advance older sessions even when unsynced; a newer visit to the same ayah loses nothing.
                existing.updated_at > modifiedAt -> keptExisting++
                else -> {
                    sessionStore.visit(session.sura, session.ayah, existing.created_at, modifiedAt)
                    readingSessionsUpdated++
                    didChange = true
                }
            }
            history?.record(ImportFingerprint.readingSession(session))
        }

        highlightsByPosition.forEach { (position, candidates) ->
            context.ensureActive()
            val existingColors = collectionStore.highlightColorsOf(position.first, position.second)
            if (existingColors.isNotEmpty()) {
                candidates.forEach { candidate ->
                    if (candidate.color in existingColors) matched++ else keptExisting++
                    history?.record(ImportFingerprint.highlight(candidate))
                }
                return@forEach
            }
            val winner = candidates.minWith(HIGHLIGHT_PRIORITY)
            collectionStore.setHighlight(winner.sura, winner.ayah, winner.color, winner.lastUpdated.toEpochMillisecondsFromPlatform())
            highlightsImported++
            didChange = true
            history?.record(ImportFingerprint.highlight(winner))
            candidates.filterNot { it === winner }.forEach { candidate ->
                keptExisting++
                history?.record(ImportFingerprint.highlight(candidate))
            }
        }

        readingBookmarks.forEach { bookmark ->
            context.ensureActive()
            val at = bookmark.lastUpdated.toEpochMillisecondsFromPlatform()
            val location = when (bookmark) {
                is ImportReadingBookmark.Ayah -> ReadingLocation.ayah(bookmark.sura, bookmark.ayah)
                is ImportReadingBookmark.Page -> ReadingBookmarkPageMapper.serverLocation(bookmark.page, bookmark.mushaf)
            }
            readingBookmarkStore.setLocation(bookmark.slot, location, at)
            readingBookmarkStore.rename(bookmark.slot, bookmark.name, at)
            readingBookmarksImported++
            didChange = true
            history?.record(ImportFingerprint.readingBookmark(bookmark))
        }

        context.ensureActive()
        return result()
    }

    /** Favorites by its canonical name; any other name links to the collection with the same name, or creates one. */
    private fun resolveCollection(name: String, createdAt: Long, modifiedAt: Long): CollectionResolution {
        if (name == DEFAULT_COLLECTION_NAME) return CollectionResolution(collectionStore.favorites().local_id, false)
        collectionStore.byName(name)?.let { return CollectionResolution(it.local_id, false) }
        didChange = true
        return CollectionResolution(collectionStore.insertUser(name, createdAt, modifiedAt), true)
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

private val HIGHLIGHT_PRIORITY = compareByDescending<ImportAyahHighlight> {
    it.lastUpdated.toEpochMillisecondsFromPlatform()
}.thenBy(ImportFingerprint::highlight)

private fun effectiveCreatedAtMillis(createdAt: PlatformDateTime?, lastUpdated: PlatformDateTime): Long =
    (createdAt ?: lastUpdated).toEpochMillisecondsFromPlatform()

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

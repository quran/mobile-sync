package com.quran.shared.pipeline

import com.quran.shared.di.AppScope
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepository
import com.quran.shared.persistence.repository.note.repository.NotesRepository
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepository
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepository
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.native.HiddenFromObjC

/**
 * The repositories [QuranDataService] reads and writes. They write the plain domain tables the sync engine
 * (`sync-v2`) syncs, so sync needs no bridge from them: [QuranDataServiceSynchronizationClientFactory] runs the
 * engine over the same database.
 */
@Inject
@SingleIn(AppScope::class)
@HiddenFromObjC
class SyncEnginePipeline(
    val bookmarksRepository: BookmarksRepository,
    val readingBookmarksRepository: ReadingBookmarksRepository,
    val collectionsRepository: CollectionsRepository,
    val collectionBookmarksRepository: CollectionBookmarksRepository,
    val notesRepository: NotesRepository,
    val readingSessionsRepository: ReadingSessionsRepository
)

package com.quran.shared.persistence.di

import com.quran.shared.di.AppScope
import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.makeDatabase
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepository
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepository
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsSynchronizationRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepository
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.note.repository.NotesRepository
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesSynchronizationRepository
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepository
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksRepositoryImpl
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepository
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsRepositoryImpl
import com.quran.shared.persistence.repository.readingsession.repository.ReadingSessionsSynchronizationRepository
import com.quran.shared.persistence.repository.PersistenceResetRepository
import com.quran.shared.persistence.repository.PersistenceResetRepositoryImpl

import dev.zacsweers.metro.Binds

@ContributesTo(AppScope::class)
@BindingContainer
abstract class PersistenceModule {

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideQuranDatabase(driverFactory: DriverFactory): QuranDatabase {
            return makeDatabase(driverFactory)
        }
    }

    @Binds
    abstract fun bindBookmarksRepository(impl: BookmarksRepositoryImpl): BookmarksRepository

    @Binds
    abstract fun bindBookmarksSynchronizationRepository(impl: BookmarksRepositoryImpl): BookmarksSynchronizationRepository

    @Binds
    abstract fun bindCollectionBookmarksRepository(impl: CollectionBookmarksRepositoryImpl): CollectionBookmarksRepository

    @Binds
    abstract fun bindCollectionBookmarksSynchronizationRepository(impl: CollectionBookmarksRepositoryImpl): CollectionBookmarksSynchronizationRepository

    @Binds
    abstract fun bindCollectionsRepository(impl: CollectionsRepositoryImpl): CollectionsRepository

    @Binds
    abstract fun bindCollectionsSynchronizationRepository(impl: CollectionsRepositoryImpl): CollectionsSynchronizationRepository

    @Binds
    abstract fun bindNotesRepository(impl: NotesRepositoryImpl): NotesRepository

    @Binds
    abstract fun bindNotesSynchronizationRepository(impl: NotesRepositoryImpl): NotesSynchronizationRepository

    @Binds
    abstract fun bindReadingBookmarksRepository(impl: ReadingBookmarksRepositoryImpl): ReadingBookmarksRepository

    @Binds
    abstract fun bindReadingBookmarksSynchronizationRepository(impl: ReadingBookmarksRepositoryImpl): ReadingBookmarksSynchronizationRepository

    @Binds
    abstract fun bindReadingSessionsRepository(impl: ReadingSessionsRepositoryImpl): ReadingSessionsRepository

    @Binds
    abstract fun bindReadingSessionsSynchronizationRepository(impl: ReadingSessionsRepositoryImpl): ReadingSessionsSynchronizationRepository

    @Binds
    abstract fun bindPersistenceResetRepository(impl: PersistenceResetRepositoryImpl): PersistenceResetRepository
}

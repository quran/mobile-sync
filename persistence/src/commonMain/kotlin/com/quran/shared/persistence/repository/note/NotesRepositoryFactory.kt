package com.quran.shared.persistence.repository.note

import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.makeDatabase
import com.quran.shared.persistence.repository.note.repository.NotesRepository
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import com.quran.shared.persistence.repository.note.repository.NotesSynchronizationRepository

/**
 * Factory for creating NotesRepository instances.
 */
object NotesRepositoryFactory {
    /**
     * Creates a new instance of NotesRepository.
     */
    fun createRepository(driverFactory: DriverFactory): NotesRepository {
        val database = makeDatabase(driverFactory)
        return NotesRepositoryImpl(database)
    }

    /**
     * Creates a new instance of NotesSynchronizationRepository.
     */
    fun createSynchronizationRepository(driverFactory: DriverFactory): NotesSynchronizationRepository {
        val database = makeDatabase(driverFactory)
        return NotesRepositoryImpl(database)
    }
}

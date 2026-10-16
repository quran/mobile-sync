package com.quran.shared.persistence.repository.note.repository

import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.util.PlatformDateTime
import kotlinx.coroutines.flow.Flow

interface NotesRepository {
    /**
     * Fetch and returns all notes, except the ones archived on QuranReflect.
     */
    suspend fun getAllNotes(): List<Note>

    /**
     * Add a note locally.
     */
    suspend fun addNote(body: String, startSura: Int, startAyah: Int, endSura: Int, endAyah: Int): Note

    suspend fun addNote(
        body: String,
        startSura: Int,
        startAyah: Int,
        endSura: Int,
        endAyah: Int,
        timestamp: PlatformDateTime
    ): Note

    /**
     * Updates a note's body by its mobile-sync ID. A note's verse ranges never change, as on the server.
     *
     * @throws com.quran.shared.persistence.RecordNotFoundException when [id] does not identify a note.
     */
    suspend fun updateNote(id: String, body: String): Note

    /**
     * Updates a note's body by its mobile-sync ID at [timestamp]. A note's verse ranges never change, as on the server.
     *
     * @throws com.quran.shared.persistence.RecordNotFoundException when [id] does not identify a note.
     */
    suspend fun updateNote(id: String, body: String, timestamp: PlatformDateTime): Note

    /**
     * Delete a note by its mobile-sync ID.
     *
     * @return `true` when an active note was deleted, or `false` when no matching active note existed.
     */
    suspend fun deleteNote(id: String): Boolean

    /**
     * Observe the notes list as a Flow.
     */
    fun getNotesFlow(): Flow<List<Note>>
}

package com.quran.shared.persistence.repository.note.repository

import com.quran.shared.persistence.model.Note

import kotlinx.coroutines.flow.Flow

interface NotesRepository {
    /**
     * Fetch and returns all notes.
     */
    suspend fun getAllNotes(): List<Note>

    /**
     * Add a note locally.
     */
    suspend fun addNote(body: String, startSura: Int, startAyah: Int, endSura: Int, endAyah: Int): Note

    /**
     * Update a note by its local ID.
     */
    suspend fun updateNote(localId: String, body: String, startSura: Int, startAyah: Int, endSura: Int, endAyah: Int): Note

    /**
     * Delete a note by its local ID.
     */
    suspend fun deleteNote(localId: String): Boolean

    /**
     * Observe the notes list as a Flow.
     */
    fun getNotesFlow(): Flow<List<Note>>
}

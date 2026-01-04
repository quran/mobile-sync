package com.quran.shared.persistence.repository.note.repository

import com.quran.shared.persistence.model.Note

interface NotesRepository {
    /**
     * Fetch and returns all notes.
     */
    suspend fun getAllNotes(): List<Note>

    /**
     * Add a note locally.
     */
    suspend fun addNote(body: String, startAyahId: Long, endAyahId: Long): Note

    /**
     * Update a note by its local ID.
     */
    suspend fun updateNote(localId: String, body: String, startAyahId: Long, endAyahId: Long): Note

    /**
     * Delete a note by its local ID.
     */
    suspend fun deleteNote(localId: String): Boolean
}

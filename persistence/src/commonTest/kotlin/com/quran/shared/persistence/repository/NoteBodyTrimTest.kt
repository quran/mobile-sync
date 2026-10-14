package com.quran.shared.persistence.repository

import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.repository.note.repository.NotesRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NoteBodyTrimTest {
    private val repository = NotesRepositoryImpl(createTestDatabase().synced)

    @Test
    fun `note bodies are added trimmed the way the server trims them`() = runTest {
        val added = repository.addNote("﻿  Reflect on it\n　", 2, 255, 2, 255, at(1_000L))

        assertEquals("Reflect on it", added.body)
        assertEquals(listOf("Reflect on it"), repository.getAllNotes().map { it.body })
    }

    @Test
    fun `note bodies are updated trimmed the way the server trims them`() = runTest {
        val note = repository.addNote("first note", 3, 1, 3, 1, at(1_000L))

        val updated = repository.updateNote(note.id, "\u001Ckept note\u001C \n", at(2_000L))

        assertEquals("\u001Ckept note\u001C", updated.body)
    }
}

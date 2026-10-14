package com.quran.shared.persistence.repository

import com.quran.shared.persistence.DuplicateCollectionNameException
import com.quran.shared.persistence.at
import com.quran.shared.persistence.createTestDatabase
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CollectionNameUniquenessTest {
    private val repository = CollectionsRepositoryImpl(createTestDatabase().synced)

    @Test
    fun `adding a case or whitespace variant returns the existing collection`() = runTest {
        val study = repository.addCollection("Study", at(1_000L))

        val lowercase = repository.addCollection("study", at(2_000L))
        val padded = repository.addCollection("﻿ study　", at(3_000L))

        assertEquals(study, lowercase)
        assertEquals(study, padded)
        assertEquals(listOf("Study"), userCollectionNames())
    }

    @Test
    fun `collection names are trimmed on add`() = runTest {
        val added = repository.addCollection("  Study  ", at(1_000L))

        assertEquals("Study", added.name)
        assertEquals(listOf("Study"), userCollectionNames())
    }

    @Test
    fun `collection names are trimmed on rename`() = runTest {
        val other = repository.addCollection("Other", at(1_000L))

        val renamed = repository.updateCollection(other.id, " Notes ", at(2_000L))

        assertEquals("Notes", renamed.name)
        assertEquals(listOf("Notes"), userCollectionNames())
    }

    @Test
    fun `renaming onto a case variant of another collection throws a duplicate name error`() = runTest {
        repository.addCollection("Study", at(1_000L))
        val other = repository.addCollection("Other", at(1_000L))

        val error = runCatching { repository.updateCollection(other.id, " STUDY ", at(2_000L)) }.exceptionOrNull()

        assertEquals("Study", (error as DuplicateCollectionNameException).name)
        assertEquals(setOf("Study", "Other"), userCollectionNames().toSet())
    }

    @Test
    fun `renaming a collection to its own case variant keeps the new display case`() = runTest {
        val study = repository.addCollection("Study", at(1_000L))

        repository.updateCollection(study.id, "STUDY", at(2_000L))

        assertEquals(listOf("STUDY"), userCollectionNames())
    }

    @Test
    fun `a renamed collection frees its old name`() = runTest {
        val study = repository.addCollection("Study", at(1_000L))
        repository.updateCollection(study.id, "Review", at(2_000L))

        val added = repository.addCollection("Study", at(3_000L))

        assertEquals(setOf("Review", "Study"), userCollectionNames().toSet())
        assertNotEquals(study.id, added.id)
    }

    private suspend fun userCollectionNames() = repository.getAllCollections().filterNot { it.isSystem }.map { it.name }
}

package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.createQuranDatabase
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Instant

class CollectionNameUniquenessTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: QuranDatabase
    private lateinit var repository: CollectionsRepositoryImpl

    @BeforeTest
    fun setup() {
        driver = TestDatabaseDriver().createDriver()
        database = createQuranDatabase(driver)
        database.collectionsQueries.deleteAll()
        repository = CollectionsRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `adding a case or whitespace variant returns the existing collection`() = runTest {
        val study = repository.addCollection("Study", at(1_000L))

        val lowercase = repository.addCollection("study", at(2_000L))
        val padded = repository.addCollection("\uFEFF study\u3000", at(3_000L))

        assertEquals(study.id, lowercase.id)
        assertEquals(study.id, padded.id)
        assertEquals(listOf("Study"), repository.getAllCollections().map { it.name })
    }

    @Test
    fun `collection names are trimmed on save`() = runTest {
        val added = repository.addCollection("  Study  ", at(1_000L))
        repository.addCollection("Other", at(1_000L)).also { repository.updateCollection(it.id, " Notes ", at(2_000L)) }

        assertEquals("Study", added.name)
        assertEquals(setOf("Study", "Notes"), repository.getAllCollections().map { it.name }.toSet())
    }

    @Test
    fun `renaming onto a case variant of another collection is rejected`() = runTest {
        repository.addCollection("Study", at(1_000L))
        val other = repository.addCollection("Other", at(1_000L))

        assertFails { repository.updateCollection(other.id, "STUDY", at(2_000L)) }

        assertEquals(setOf("Study", "Other"), repository.getAllCollections().map { it.name }.toSet())
    }

    @Test
    fun `renaming a collection to its own case variant keeps the new display case`() = runTest {
        val study = repository.addCollection("Study", at(1_000L))

        repository.updateCollection(study.id, "STUDY", at(2_000L))

        assertEquals(listOf("STUDY"), repository.getAllCollections().map { it.name })
    }

    private fun at(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()
}

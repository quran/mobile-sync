package com.quran.shared.persistence.repository

import app.cash.sqldelight.db.SqlDriver
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.QuranDatabase
import com.quran.shared.persistence.TestDatabaseDriver
import com.quran.shared.persistence.input.RemoteCollection
import com.quran.shared.persistence.model.AyahHighlightColor
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.persistence.util.toPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class RemoteCollectionNameLinkTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: QuranDatabase
    private lateinit var collections: CollectionsRepositoryImpl
    private lateinit var collectionBookmarks: CollectionBookmarksRepositoryImpl

    @BeforeTest
    fun setup() {
        driver = TestDatabaseDriver().createDriver()
        database = QuranDatabase(driver)
        collections = CollectionsRepositoryImpl(database)
        collectionBookmarks = CollectionBookmarksRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `remote update links a used seeded highlight collection and unblocks its memberships`() = runTest {
        collectionBookmarks.setHighlight(40, 3, AyahHighlightColor.BLUE, at(5_000L))
        val seeded = database.collectionsQueries.getCollectionByName(BLUE).executeAsOne()
        val pendingCollections = collections.fetchMutatedCollections()
        assertEquals(emptyList(), collectionBookmarks.fetchMutatedCollectionBookmarks())

        collections.applyRemoteChanges(
            updatesToPersist = listOf(remoteUpdate(BLUE, "remote-blue")),
            localMutationsToClear = pendingCollections
        )

        val linked = database.collectionsQueries.getCollectionByRemoteId("remote-blue").executeAsOne()
        assertEquals(seeded.local_id, linked.local_id)
        assertEquals(1L, linked.is_system)
        assertEquals(6L, database.collectionsQueries.countAll().executeAsOne())
        assertEquals(emptyList(), collections.fetchMutatedCollections())
        val membership = collectionBookmarks.fetchMutatedCollectionBookmarks().single()
        assertEquals(Mutation.CREATED, membership.mutation)
        assertEquals("remote-blue", membership.model.collectionRemoteId)
        assertEquals(40, membership.model.sura)
        assertEquals(3, membership.model.ayah)
    }

    @Test
    fun `remote update links a pending local collection with the same name`() = runTest {
        val local = collections.addCollection(STUDY, at(1_000L))

        collections.applyRemoteChanges(
            updatesToPersist = listOf(remoteUpdate(STUDY, "remote-study", isSystem = false)),
            localMutationsToClear = collections.fetchMutatedCollections()
        )

        val linked = database.collectionsQueries.getCollectionByRemoteId("remote-study").executeAsOne()
        assertEquals(local.id, linked.local_id.toString())
        assertEquals(listOf(local.id), collections.getAllCollections().filter { it.name == STUDY }.map { it.id })
        assertEquals(emptyList(), collections.fetchMutatedCollections())
    }

    @Test
    fun `remote update does not link a deleted never-synced collection with the same name`() = runTest {
        val local = collections.addCollection(STUDY, at(1_000L))
        collections.deleteCollection(local.id)

        collections.applyRemoteChanges(
            updatesToPersist = listOf(remoteUpdate(STUDY, "remote-study", isSystem = false)),
            localMutationsToClear = emptyList()
        )

        assertNull(database.collectionsQueries.getCollectionByLocalId(local.id.toLong()).executeAsOne().remote_id)
        val remote = database.collectionsQueries.getCollectionByRemoteId("remote-study").executeAsOne()
        assertEquals(STUDY, remote.name)
        assertEquals(0L, remote.deleted)
        assertEquals(emptyList(), collections.fetchMutatedCollections())
    }

    private fun remoteUpdate(name: String, remoteId: String, isSystem: Boolean = true) = RemoteModelMutation(
        model = RemoteCollection(
            name = name,
            lastUpdated = at(3_000L),
            createdAt = at(1_000L),
            isSystem = isSystem
        ),
        remoteID = remoteId,
        mutation = Mutation.MODIFIED
    )

    private fun at(milliseconds: Long) = Instant.fromEpochMilliseconds(milliseconds).toPlatform()

    private companion object {
        const val BLUE = "system:highlights:blue"
        const val STUDY = "Study"
    }
}

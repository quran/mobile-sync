package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.syncFailedLocally
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Catalog section 2.4: collections. */
class CollectionScenariosTest {

    @Test
    fun `COL-01 a create syncs`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")

        phone.data.addCollection("Study")
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        assertEquals(listOf("Study"), server.state(ALICE).collections.map { it.name })
        assertEquals(listOf("Study"), tablet.collectionNames())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `COL-02 a rename after the echo was consumed`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        val study = phone.data.addCollection("Study")
        syncUntilQuiescent(phone)

        phone.data.updateCollection(study.id, "Memorize")
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        assertEquals(listOf("Memorize"), server.state(ALICE).collections.map { it.name })
        assertEquals(listOf("Memorize"), tablet.collectionNames())
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `COL-03 a rename before the next sync isn't reverted by the echo`() = syncScenario {
        val (phone) = signedIn("phone")
        val study = phone.data.addCollection("Study")
        assertSucceeded(phone.syncNow())

        phone.data.updateCollection(study.id, "Memorize")

        knownIssue(
            "S2",
            "This device's own echo overwrites a newer local rename",
            expect = failureMentions("local name", "Study")
        ) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("Memorize"), phone.collectionNames(), "local name")
            assertEquals(listOf("Memorize"), server.state(ALICE).collections.map { it.name }, "server name")
        }
    }

    @Test
    fun `COL-05 deleting a collection with members`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        val study = phone.data.addCollection("Study")
        phone.data.addAyahBookmarkToCollection(study.id, 2, 255)
        phone.data.addAyahBookmarkToCollection(study.id, 3, 18)
        phone.addToFavorites(3, 18)
        syncUntilQuiescent(phone, tablet)

        phone.data.deleteCollection(study.id)
        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())

        val state = server.state(ALICE)
        assertNull(state.collectionNamed("Study"))
        assertEquals(emptyList(), tablet.collectionNames())
        assertEquals(setOf("3:18"), state.favorites)
        listOf(phone, tablet).forEach { assertEquals(listOf("3:18"), it.favoriteVerses(), "${it.name} Favorites") }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `COL-06 a deleted never-sent collection doesn't delete another device's collection`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.deleteCollection(phone.data.addCollection("Study").id)
        tablet.data.addCollection("Study")
        assertSucceeded(tablet.syncNow())

        knownIssue(
            "S1",
            "A never-sent collection tombstone binds another device's collection and deletes it",
            expect = failureMentions("tablet's Study on the server")
        ) {
            assertSucceeded(phone.syncNow())
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("Study"), server.state(ALICE).collections.map { it.name }, "tablet's Study on the server")
            assertSucceeded(tablet.syncNow())
            assertEquals(listOf("Study"), tablet.collectionNames(), "tablet's Study on the tablet")
        }
    }

    @Test
    fun `COL-07 repeated create and delete of one name doesn't lose a remote collection`() = syncScenario {
        val (phone) = signedIn("phone")
        repeat(2) { phone.data.deleteCollection(phone.data.addCollection("Study").id) }
        web(ALICE).createCollection("study")

        // The catalog predicted the "ambiguous tombstones" throw. For collections the sync doesn't
        // throw: the web's create binds a tombstone, never shows, and the second sync deletes it
        // on the server.
        knownIssue(
            "S1",
            "Never-sent collection tombstones bind a remote create with the same name and delete it",
            expect = failureMentions("the web's study on the server", "expected:<[study]> but was:<[]>")
        ) {
            assertSucceeded(phone.syncNow())
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("study"), server.state(ALICE).collections.map { it.name }, "the web's study on the server")
            assertEquals(listOf("study"), phone.collectionNames(), "the web's study on the phone")
        }
    }

    @Test
    fun `COL-08 two devices create the same name offline`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 2, 255)
        tablet.data.addAyahBookmarkToCollection(tablet.data.addCollection("Study").id, 3, 18)

        assertSucceeded(phone.syncNow())
        assertSucceeded(tablet.syncNow())
        syncUntilQuiescent(phone, tablet)

        val state = server.state(ALICE)
        assertEquals(listOf("Study"), state.collections.map { it.name })
        assertEquals(setOf("2:255", "3:18"), state.versesIn(state.collectionNamed("Study")!!.id))
        listOf(phone, tablet).forEach {
            assertEquals(listOf("Study"), it.collectionNames(), it.name)
            assertEquals(listOf("2:255", "3:18"), it.versesIn("Study"), it.name)
        }
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `COL-09 a remote rename onto a name held by a local unsynced collection`() = syncScenario {
        val web = web(ALICE)
        val study = web.createCollection("Study")
        web.addToCollection(study, 3, 18)
        val (phone) = signedIn("phone")
        web.renameCollection(study, "Memorize")
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Memorize").id, 2, 255)

        knownIssue(
            "S9",
            "A remote collection rename onto a locally held name violates the unique index",
            expect = syncFailedLocally("SQLiteException")
        ) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("Memorize"), phone.collectionNames())
            assertEquals(listOf("2:255", "3:18"), phone.versesIn("Memorize"))
            syncUntilQuiescent(phone)
            assertConverged(ALICE, phone)
        }
    }
}

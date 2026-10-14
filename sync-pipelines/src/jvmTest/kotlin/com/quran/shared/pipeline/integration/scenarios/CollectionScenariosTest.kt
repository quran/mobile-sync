package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.knownIssue
import com.quran.shared.pipeline.integration.mutation
import com.quran.shared.pipeline.integration.syncFailedLocally
import com.quran.shared.pipeline.integration.syncRejectedWith
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Collections. */
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

    @Test
    fun `COL-04 a rename after adding a verse isn't reverted by the membership bump`() = syncScenario {
        val (phone) = signedIn("phone")
        val study = phone.data.addCollection("Study")
        syncUntilQuiescent(phone)

        phone.data.addAyahBookmarkToCollection(study.id, 2, 255)
        assertSucceeded(phone.syncNow())
        phone.data.updateCollection(study.id, "Memorize")

        knownIssue(
            "S2",
            "The echo of a membership's collection bump overwrites a newer local rename",
            expect = failureMentions("local name", "expected:<[Memorize]> but was:<[Study]>")
        ) {
            assertSucceeded(phone.syncNow())
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("Memorize"), phone.collectionNames(), "local name")
            assertEquals(listOf("Memorize"), server.state(ALICE).collections.map { it.name }, "server name")
        }
    }

    @Test
    fun `COL-10 a chain of web renames applies in one sync`() = syncScenario {
        val web = web(ALICE)
        val a = web.createCollection("A")
        val b = web.createCollection("B")
        val (phone) = signedIn("phone")

        web.renameCollection(b, "C")
        web.renameCollection(a, "B")
        web.renameCollection(b, "D")

        knownIssue(
            "S9",
            "A chain of remote renames violates the unique name index",
            expect = syncFailedLocally("SQLiteException")
        ) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("B", "D"), phone.collectionNames())
            assertConverged(ALICE, phone)
        }
    }

    @Test
    fun `COL-11 a local rename collides with a remote create`() = syncScenario {
        val web = web(ALICE)
        web.addToCollection(web.createCollection("Notes"), 2, 255)
        val (phone) = signedIn("phone")

        phone.data.updateCollection(phone.collectionId("Notes"), "Memorize")
        web.addToCollection(web.createCollection("Memorize"), 3, 18)

        knownIssue(
            "S9",
            "A local rename onto a remotely created name diverges silently",
            // The sync succeeds, but the phone keeps its unsent rename and never shows the web's Memorize.
            expect = failureMentions(
                "expected:<UserDataSnapshot(notes=[], favorites=[], collections=[Memorize: 3:18, Notes: 2:255]",
                "but was:<UserDataSnapshot(notes=[], favorites=[], collections=[Memorize: 2:255]"
            )
        ) {
            assertSucceeded(phone.syncNow())
            syncUntilQuiescent(phone)
            assertConverged(ALICE, phone)
            assertTrue(phone.versesIn("Memorize").orEmpty().contains("3:18"), "the remote Memorize's verse on the phone")
        }
    }

    @Test
    fun `COL-12 renaming to another existing name fails cleanly`() = syncScenario {
        val (phone) = signedIn("phone")
        val study = phone.data.addCollection("Study")
        phone.data.addCollection("Memorize")
        syncUntilQuiescent(phone)

        val failure = assertFails { phone.data.updateCollection(study.id, "memorize") }

        // The API has no duplicate-name error type yet, so this accepts the API's validation family,
        // IllegalArgumentException, as TextLengthLimitException and the reserved-name checks use.
        // Tighten it to the type S16's fix adds.
        knownIssue(
            "S16",
            "Renaming a collection to another existing name throws an untyped SQLite exception",
            expect = failureMentions("a typed duplicate-name error", "SQLiteException")
        ) {
            assertTrue(failure is IllegalArgumentException, "a typed duplicate-name error, not ${failure::class.qualifiedName}")
        }
        assertEquals(listOf("Memorize", "Study"), phone.collectionNames())
        val run = phone.syncNow()
        assertSucceeded(run)
        assertEquals(0, run.posts, "nothing pushed")
        assertEquals(listOf("Memorize", "Study"), server.state(ALICE).collections.map { it.name }.sorted())
    }

    @Test
    fun `COL-13 concurrent renames keep the newer one`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addCollection("Study", at(0))
        syncUntilQuiescent(phone, tablet)

        tablet.data.updateCollection(tablet.collectionId("Study"), "A", at(1_000))
        assertSucceeded(tablet.syncNow())
        phone.data.updateCollection(phone.collectionId("Study"), "B", at(2_000))

        knownIssue(
            "S17",
            "Concurrent collection renames resolve remote-wins, not newest-wins",
            expect = failureMentions("the phone's name", "expected:<[B]> but was:<[A]>")
        ) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("B"), phone.collectionNames(), "the phone's name")
            syncUntilQuiescent(phone, tablet)
            assertEquals(listOf("B"), server.state(ALICE).collections.map { it.name })
            assertConverged(ALICE, phone, tablet)
        }
    }

    @Test
    fun `COL-14 a local rename beats a remote delete`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 2, 255)
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        web.deleteCollection(web.collectionId("Study")!!)
        phone.data.updateCollection(phone.collectionId("Study"), "Memorize")

        knownIssue(
            "S15",
            "A remote delete beats a local collection rename",
            expect = failureMentions("the phone's collections", "expected:<[Memorize]> but was:<[]>")
        ) {
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("Memorize"), phone.collectionNames(), "the phone's collections")
            assertEquals(listOf("2:255"), phone.versesIn("Memorize"))
            syncUntilQuiescent(phone)
            val state = server.state(ALICE)
            assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed("Memorize")!!.id))
            assertConverged(ALICE, phone)
        }
    }

    @Test
    fun `COL-15 a remote rename beats a local delete`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 2, 255)
        syncUntilQuiescent(phone)
        val web = web(ALICE)
        val study = web.collectionId("Study")!!

        web.renameCollection(study, "Memorize")
        phone.data.deleteCollection(phone.collectionId("Study"))
        assertSucceeded(phone.syncNow())
        syncUntilQuiescent(phone)

        assertEquals(listOf("Memorize"), phone.collectionNames(), "the remote rename brings the collection back")
        // The catalog left this unknown. The rename wins over the delete, but the delete's removal of
        // 2:255 from the collection is still pushed, so both sides end with an empty Memorize.
        knownIssue(
            "S26",
            "A remote rename restores a locally deleted collection, but its memberships' removals are still pushed",
            expect = failureMentions("the phone's Memorize", "expected:<[2:255]> but was:<[]>")
        ) {
            assertEquals(listOf("2:255"), phone.versesIn("Memorize"), "the phone's Memorize")
            assertEquals(setOf("2:255"), server.state(ALICE).versesIn(study), "the server keeps the membership")
            assertConverged(ALICE, phone)
        }
    }

    @Test
    fun `COL-16 a web user collection named Favorites`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.addToFavorites(1, 1)
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        web.addToCollection(web.createCollection("Favorites"), 4, 1)
        assertSucceeded(phone.syncNow())

        // Nothing is silently dropped: the default Favorites and the web's own "Favorites" both show.
        knownIssue(
            "S9",
            "A web user collection named Favorites is skipped as colliding with the default",
            // The catalog predicted S9. The sync succeeds, and the web's Favorites never shows.
            expect = failureMentions("the web's Favorites", "expected:<[4:1]> but was:<null>")
        ) {
            assertEquals(listOf("1:1"), phone.favoriteVerses(), "the default Favorites")
            assertEquals(listOf("4:1"), phone.versesIn("Favorites"), "the web's Favorites")
            assertConverged(ALICE, phone)
        }
    }

    @Test
    fun `COL-17 an older app's protected color collection`() = syncScenario {
        val web = web(ALICE)
        val blue = web.createLegacyColorCollection("blue")
        web.addToCollection(blue, 2, 255)
        val (phone) = signedIn("phone")
        assertEquals(listOf("2:255"), phone.versesIn("blue"))

        knownIssue(
            "S11",
            "Renaming an older app's protected color collection gets 403 forever",
            expect = syncRejectedWith(403, carrying = mutation("COLLECTION", "UPDATE"))
        ) {
            // Either the API refuses the change, or the sync reverts it after the 403.
            runCatching { phone.data.updateCollection(phone.collectionId("blue"), "Renamed") }
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("blue"), phone.collectionNames())
            runCatching { phone.data.deleteCollection(phone.collectionId("blue")) }
            assertSucceeded(phone.syncNow())
            assertEquals(listOf("2:255"), phone.versesIn("blue"))
            assertEquals(setOf("2:255"), server.state(ALICE).versesIn(blue))
            assertConverged(ALICE, phone)
        }
    }

    @Test
    fun `COL-18 reserved names are rejected locally`() = syncScenario {
        val (phone) = signedIn("phone")

        assertFailsWith<IllegalArgumentException> { phone.data.addCollection("Favorites") }
        assertFailsWith<IllegalArgumentException> { phone.data.addCollection(HIGHLIGHTS_BLUE) }

        assertEquals(emptyList(), phone.collectionNames())
        assertEquals(emptyList(), phone.highlightList())
        val run = phone.syncNow()
        assertSucceeded(run)
        assertEquals(0, run.posts)
        assertEquals(emptyList(), server.state(ALICE).collections)
    }

    @Test
    fun `COL-19 the default collection can't be deleted or renamed`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.addToFavorites(2, 255)
        syncUntilQuiescent(phone)
        val favorites = phone.favoritesId()

        // The catalog also covers highlight collections, but the public API never hands out a
        // highlight collection's id: collectionsWithBookmarks hides them, and highlights carry no id.
        assertFailsWith<IllegalArgumentException> { phone.data.deleteCollection(favorites) }
        assertFailsWith<IllegalArgumentException> { phone.data.updateCollection(favorites, "Renamed") }

        assertEquals(listOf("2:255"), phone.favoriteVerses())
        val run = phone.syncNow()
        assertSucceeded(run)
        assertEquals(0, run.posts, "no mutation")
        assertEquals(setOf("2:255"), server.state(ALICE).favorites)
        assertConverged(ALICE, phone)
    }
}

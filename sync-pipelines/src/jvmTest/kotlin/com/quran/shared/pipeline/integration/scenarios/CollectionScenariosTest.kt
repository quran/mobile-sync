package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.persistence.DuplicateCollectionNameException
import com.quran.shared.persistence.ReadOnlyCollectionException
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.mutation
import com.quran.shared.pipeline.integration.server.ClientTimes
import com.quran.shared.pipeline.integration.syncScenario
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

        assertSucceeded(phone.syncNow())
        assertEquals(listOf("Memorize"), phone.collectionNames(), "local name")
        assertEquals(listOf("Memorize"), server.state(ALICE).collections.map { it.name }, "server name")
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

        assertSucceeded(phone.syncNow())
        assertSucceeded(phone.syncNow())
        assertEquals(listOf("Study"), server.state(ALICE).collections.map { it.name }, "tablet's Study on the server")
        assertSucceeded(tablet.syncNow())
        assertEquals(listOf("Study"), tablet.collectionNames(), "tablet's Study on the tablet")
    }

    @Test
    fun `COL-07 repeated create and delete of one name doesn't lose a remote collection`() = syncScenario {
        val (phone) = signedIn("phone")
        repeat(2) { phone.data.deleteCollection(phone.data.addCollection("Study").id) }
        web(ALICE).createCollection("study")

        // A create deleted before it was sent leaves no tombstone, so nothing can bind the web's create.
        assertSucceeded(phone.syncNow())
        assertSucceeded(phone.syncNow())
        assertEquals(listOf("study"), server.state(ALICE).collections.map { it.name }, "the web's study on the server")
        assertEquals(listOf("study"), phone.collectionNames(), "the web's study on the phone")
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

        assertSucceeded(phone.syncNow())
        assertEquals(listOf("Memorize"), phone.collectionNames())
        assertEquals(listOf("2:255", "3:18"), phone.versesIn("Memorize"))
        syncUntilQuiescent(phone)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `COL-04 a rename after adding a verse isn't reverted by the membership bump`() = syncScenario {
        val (phone) = signedIn("phone")
        val study = phone.data.addCollection("Study")
        syncUntilQuiescent(phone)

        phone.data.addAyahBookmarkToCollection(study.id, 2, 255)
        assertSucceeded(phone.syncNow())
        phone.data.updateCollection(study.id, "Memorize")

        assertSucceeded(phone.syncNow())
        assertSucceeded(phone.syncNow())
        assertEquals(listOf("Memorize"), phone.collectionNames(), "local name")
        assertEquals(listOf("Memorize"), server.state(ALICE).collections.map { it.name }, "server name")
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

        assertSucceeded(phone.syncNow())
        assertEquals(listOf("B", "D"), phone.collectionNames())
        assertConverged(ALICE, phone)
    }

    @Test
    fun `COL-11 a local rename collides with a remote create`() = syncScenario {
        val web = web(ALICE)
        web.addToCollection(web.createCollection("Notes"), 2, 255)
        val (phone) = signedIn("phone")

        phone.data.updateCollection(phone.collectionId("Notes"), "Memorize")
        web.addToCollection(web.createCollection("Memorize"), 3, 18)

        assertSucceeded(phone.syncNow())
        syncUntilQuiescent(phone)
        assertConverged(ALICE, phone)
        assertTrue(phone.versesIn("Memorize").orEmpty().contains("3:18"), "the remote Memorize's verse on the phone")
    }

    /** The app's collection list is newest first by creation time, on every device: a synced collection keeps its own. */
    @Test
    fun `collections are listed newest first by creation time`() = syncScenario {
        val (phone, tablet) = signedIn("phone", "tablet")
        phone.data.addCollection("Oldest", at(1_000))
        phone.data.addCollection("Newest", at(3_000))
        web(ALICE).createCollection("From the web", ClientTimes(T0 + 2_000, T0 + 2_000))

        syncUntilQuiescent(phone, tablet)

        val expected = listOf("Favorites", "Newest", "From the web", "Oldest")
        listOf(phone, tablet).forEach { device ->
            assertEquals(expected, device.data.collectionsWithBookmarks.first().map { it.collection.name }, device.name)
        }
    }

    @Test
    fun `COL-12 renaming to another existing name fails cleanly`() = syncScenario {
        val (phone) = signedIn("phone")
        val study = phone.data.addCollection("Study")
        phone.data.addCollection("Memorize")
        syncUntilQuiescent(phone)

        val failure = assertFails { phone.data.updateCollection(study.id, "memorize") }

        // A typed error, in the API's validation family (IllegalArgumentException), like
        // TextLengthLimitException and the reserved-name checks.
        assertTrue(failure is IllegalArgumentException, "a typed duplicate-name error, not ${failure::class.qualifiedName}")
        assertEquals("Memorize", assertIs<DuplicateCollectionNameException>(failure).name)

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

        assertSucceeded(phone.syncNow())
        assertEquals(listOf("B"), phone.collectionNames(), "the phone's name")
        syncUntilQuiescent(phone, tablet)
        assertEquals(listOf("B"), server.state(ALICE).collections.map { it.name })
        assertConverged(ALICE, phone, tablet)
    }

    @Test
    fun `COL-14 a local rename beats a remote delete`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.data.addAyahBookmarkToCollection(phone.data.addCollection("Study").id, 2, 255)
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        web.deleteCollection(web.collectionId("Study")!!)
        phone.data.updateCollection(phone.collectionId("Study"), "Memorize")

        assertSucceeded(phone.syncNow())
        assertEquals(listOf("Memorize"), phone.collectionNames(), "the phone's collections")
        assertEquals(listOf("2:255"), phone.versesIn("Memorize"))
        syncUntilQuiescent(phone)
        val state = server.state(ALICE)
        assertEquals(setOf("2:255"), state.versesIn(state.collectionNamed("Memorize")!!.id))
        assertConverged(ALICE, phone)
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
        // The remote rename restores the collection with the verses its local delete removed.
        assertEquals(listOf("2:255"), phone.versesIn("Memorize"), "the phone's Memorize")
        assertEquals(setOf("2:255"), server.state(ALICE).versesIn(study), "the server keeps the membership")
        assertConverged(ALICE, phone)
    }

    @Test
    fun `COL-16 a web user collection named Favorites`() = syncScenario {
        val (phone) = signedIn("phone")
        phone.addToFavorites(1, 1)
        syncUntilQuiescent(phone)
        val web = web(ALICE)

        val userFavorites = web.createCollection("Favorites")
        web.addToCollection(userFavorites, 4, 1)
        val run = phone.syncNow()

        assertSucceeded(run)
        assertEquals(0, run.posts, "nothing pushed")
        assertEquals(setOf("4:1"), server.state(ALICE).versesIn(userFavorites), "the server keeps the web's Favorites")
        assertEquals(setOf("1:1"), server.state(ALICE).favorites)

        // Nothing is silently dropped: the default Favorites keeps 1:1, and the web's "Favorites" is a
        // user collection under its own name, holding 4:1.
        assertEquals(listOf("1:1"), phone.favoriteVerses(), "the default Favorites")
        assertEquals(listOf("Favorites"), phone.collectionNames(), "the web's user collection")
        assertEquals(listOf("4:1"), phone.versesIn("Favorites"))
        assertConverged(ALICE, phone)
    }

    @Test
    fun `COL-17 an older app's protected color collection`() = syncScenario {
        val web = web(ALICE)
        val blue = web.createLegacyColorCollection("blue")
        web.addToCollection(blue, 2, 255)
        val (phone) = signedIn("phone")
        assertEquals(listOf("2:255"), phone.versesIn("blue"))

        // The API refuses the change with a typed error: older apps' color collections arrive read-only.
        assertFailsWith<ReadOnlyCollectionException> { phone.data.updateCollection(phone.collectionId("blue"), "Renamed") }
        assertSucceeded(phone.syncNow())
        assertEquals(listOf("blue"), phone.collectionNames())
        assertFailsWith<ReadOnlyCollectionException> { phone.data.deleteCollection(phone.collectionId("blue")) }
        assertSucceeded(phone.syncNow())
        assertEquals(listOf("2:255"), phone.versesIn("blue"))
        assertEquals(setOf("2:255"), server.state(ALICE).versesIn(blue))
        assertConverged(ALICE, phone)
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

        // The public API never hands out a highlight collection's id: collectionsWithBookmarks
        // hides them, and highlights carry no id.
        assertFailsWith<IllegalArgumentException> { phone.data.deleteCollection(favorites) }
        assertFailsWith<IllegalArgumentException> { phone.data.updateCollection(favorites, "Renamed") }

        assertEquals(listOf("2:255"), phone.favoriteVerses())
        val run = phone.syncNow()
        assertSucceeded(run)
        assertEquals(0, run.posts, "no mutation")
        assertEquals(setOf("2:255"), server.state(ALICE).favorites)
        assertConverged(ALICE, phone)
    }

    @Test
    fun `COL-20 a remote rename onto a name a local pending rename took`() = syncScenario {
        val web = web(ALICE)
        // "Study" is created first, so the phone writes it back before "Notes": its rename to "Memorize" lands
        // while "Notes" still holds that name locally. In the other order the revert of "Notes" would free the
        // name first, and the scenario would pass even without the write-back's temporary keys.
        val study = web.createCollection("Study")
        web.addToCollection(study, 3, 18)
        web.addToCollection(web.createCollection("Notes"), 2, 255)
        val (phone) = signedIn("phone")

        web.renameCollection(study, "Memorize")
        phone.data.updateCollection(phone.collectionId("Notes"), "Memorize")

        // The server took the web's rename first, so the phone's clashing rename is reverted, as a
        // local rename onto an existing name is refused (COL-12), and never sent; no collection or verse is lost.
        val run = phone.syncNow()
        assertSucceeded(run)
        assertEquals(0, run.posts, "the clashing rename is never sent")
        syncUntilQuiescent(phone)
        assertEquals(listOf("Memorize", "Notes"), phone.collectionNames())
        assertEquals(listOf("3:18"), phone.versesIn("Memorize"))
        assertEquals(listOf("2:255"), phone.versesIn("Notes"))
        assertConverged(ALICE, phone)
    }

    @Test
    fun `COL-21 a web swap of two names applies in one sync`() = syncScenario {
        val web = web(ALICE)
        val a = web.createCollection("A")
        web.addToCollection(a, 2, 255)
        val b = web.createCollection("B")
        web.addToCollection(b, 3, 18)
        val (phone) = signedIn("phone")

        // The server collapses the chain to A's UPDATE "B" and B's UPDATE "A": a cycle on the unique name.
        web.renameCollection(a, "Swap")
        web.renameCollection(b, "A")
        web.renameCollection(a, "B")

        assertSucceeded(phone.syncNow())
        assertEquals(listOf("A", "B"), phone.collectionNames())
        assertEquals(listOf("3:18"), phone.versesIn("A"))
        assertEquals(listOf("2:255"), phone.versesIn("B"))
        assertConverged(ALICE, phone)
    }

    @Test
    fun `COL-22 a web user collection named Favorites reaches a new device beside the seeded default`() = syncScenario {
        // Not a rename: on the first sync the unbound local Favorites links to `__default__` (uniqueness repair)
        // while a user collection with the same name arrives, and the two must stay apart.
        val web = web(ALICE)
        web.addToFavorites(1, 1)
        val userFavorites = web.createCollection("Favorites")
        web.addToCollection(userFavorites, 4, 1)
        val phone = device("phone")
        phone.addToFavorites(2, 2)

        assertSucceeded(phone.login(ALICE))
        syncUntilQuiescent(phone)

        assertEquals(listOf("1:1", "2:2"), phone.favoriteVerses(), "the default Favorites")
        assertEquals(listOf("Favorites"), phone.collectionNames(), "the web's user collection")
        assertEquals(listOf("4:1"), phone.versesIn("Favorites"))
        assertEquals(setOf("4:1"), server.state(ALICE).versesIn(userFavorites))
        assertConverged(ALICE, phone)
    }
}

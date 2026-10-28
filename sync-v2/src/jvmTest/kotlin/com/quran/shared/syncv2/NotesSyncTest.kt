package com.quran.shared.syncv2

import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.HttpError
import com.quran.shared.syncv2.types.NoteRange
import com.quran.shared.syncv2.types.syncTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Notes: a type without a natural key. No revival or uniqueness repair; the outbox uses outboxKey, and equal new notes
 * link by linkKey (see [EqualNewNotesTest]).
 */
class NotesSyncTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    /** A note created on the web with several ranges keeps all of them, in order, through an edit. */
    @Test
    fun `a note with several ranges keeps all of them through an edit`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            Remote(server).createNote("A note on two passages", "2:255-2:256", "3:18-3:18")
            device.settle()
            val note = device.notes.notes().single()
            assertEquals(listOf(NoteRange(2, 255, 2, 256), NoteRange(3, 18, 3, 18)), note.ranges)

            device.notes.updateNote(note.localId, "An edited note on two passages", t(1))
            device.settle()

            assertEquals(listOf("An edited note on two passages"), Remote(server).noteBodies())
            assertEquals(listOf(listOf("2:255-2:256", "3:18-3:18")), Remote(server).noteRanges())
            assertEquals(note.ranges, device.notes.notes().single().ranges)
        }
    }

    /** The server's note UPDATE accepts only the body and client timestamps; `ranges` would get 422 forever. */
    @Test
    fun `an edit sends only the body and its time`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val note = device.addNote("First body of it", "2:255", t(0))
            device.settle()

            device.notes.updateNote(note, "Second body of it", t(1))
            device.settle()

            val update = server.postBodies.last()["mutations"]!!.jsonArray.single().jsonObject
            assertEquals(setOf("body", "clientUpdatedAt"), (update["data"] as JsonObject).keys)
            assertEquals(listOf("Second body of it"), Remote(server).noteBodies())
        }
    }

    @Test
    fun `a body the server would refuse is held and the rest syncs`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.addNote("12345", "1:1", t(0))
            device.addNote("a".repeat(9_998) + "&", "1:2", t(1))
            device.addNote("Long enough", "1:3", t(2))

            assertEquals(listOf("BODY_LENGTH", "BODY_LENGTH"), device.settle().held.map { it.reason })
            assertEquals(listOf("Long enough"), Remote(server).noteBodies())
        }
    }

    /**
     * A synced note edited too short is held on its UPDATE; deleting it then is a new change, not that hold: the
     * DELETE is pushed and the note is no longer listed as held.
     */
    @Test
    fun `deleting a note held for an edit pushes the delete`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val note = device.addNote("Long enough to sync", "1:1", t(0))
            device.settle()
            device.notes.updateNote(note, "Bad", t(1))
            assertEquals(listOf(HeldItem("NOTE", note, "BODY_LENGTH")), device.settle().held)

            device.notes.deleteNote(note)

            assertEquals(emptyList(), SyncEngineV2.held(device.database, syncTypes(device.database)), "released before any sync")
            assertEquals(emptyList(), device.settle().held)
            assertEquals(emptyList(), Remote(server).noteBodies())
        }
    }

    /** IMP-07: a legacy note naming a verse that doesn't exist gets 500 from the server; it is held up front. */
    @Test
    fun `a note on a verse that doesn't exist is held, not retried forever`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.notes.addNote("On a missing verse", listOf(NoteRange(1, 8, 1, 8)), t(0))
            device.notes.addNote("On a reversed range", listOf(NoteRange(2, 5, 2, 1)), t(1))
            device.addNote("On a real verse", "1:7", t(2))

            assertEquals(listOf("INVALID_RANGE", "INVALID_RANGE"), device.settle().held.map { it.reason })
            assertEquals(listOf("On a real verse"), Remote(server).noteBodies())
            assertEquals(listOf(200), server.postStatuses)
        }
    }

    /**
     * Archiving on QuranReflect is a 30-day trash. The note stays a row here, marked archived (app-facing queries
     * hide it), and nothing is deleted or pushed.
     */
    @Test
    fun `a note archived on QuranReflect stays, marked archived`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.addNote("Archived on QuranReflect", "1:2", t(0))
            device.settle()
            val posts = server.postBodies.size

            Remote(server).archiveNote(Remote(server).noteIds().single())
            device.settle()

            val note = device.notes.notes().single()
            assertEquals("Archived on QuranReflect", note.body)
            assertNotNull(note.archivedAt)
            assertEquals(posts, server.postBodies.size)
            assertEquals(1, Remote(server).noteIds().size)
        }
    }

    /**
     * An edit made here while the note was archived there is an UPDATE to the archived note (the server's UPDATE
     * accepts it). It stays archived with the edit; an unarchive brings back one note carrying the edit.
     */
    @Test
    fun `an edit to a note archived elsewhere is kept and comes back with an unarchive`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val note = device.addNote("Before the archive", "2:255", t(0))
            device.settle()
            val remote = Remote(server)
            val id = remote.noteIds().single()
            remote.archiveNote(id)
            device.notes.updateNote(note, "Edited while archived", t(1))

            device.settle()
            assertEquals(listOf("Edited while archived"), remote.noteBodies())
            assertEquals(setOf(id), remote.archivedNoteIds())
            assertNotNull(device.notes.notes().single().archivedAt)

            remote.unarchiveNote(id)
            device.settle()

            assertEquals(setOf(id), remote.noteIds(), "one note, never recreated")
            assertEquals(emptySet(), remote.archivedNoteIds())
            val back = device.notes.notes().single()
            assertEquals("Edited while archived", back.body)
            assertNull(back.archivedAt)
        }
    }

    /** Notes: the CREATE landed, its response was lost, and the user edited before the retry. */
    @Test
    fun `a lost note create is bound by clientCreatedAt and ranges instead of duplicated`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val note = device.addNote("Patience is light", "2:153", t(0))
            server.loseNextPostResponse = true

            assertIs<SyncResult.Failed>(device.sync())
            device.notes.updateNote(note, "Patience is a light", t(1))
            device.settle()

            assertEquals(listOf("Patience is a light"), Remote(server).noteBodies())
            assertEquals(listOf("UPDATE NOTE"), server.mutationTypesOfLastPost())
        }
    }

    /** The content no longer equals what was pushed, but `(clientCreatedAt, ranges)` still identifies the create. */
    @Test
    fun `a lost note create that another device edits before the next fetch is not duplicated`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.addNote("Patience is light", "2:153", t(0))
            server.loseNextPostResponse = true

            assertIs<SyncResult.Failed>(device.sync())
            val remote = Remote(server)
            remote.editNote(remote.noteIds().single(), "Patience is light, edited on the web")
            device.settle()

            assertEquals(listOf("Patience is light, edited on the web"), remote.noteBodies())
            assertEquals(listOf("Patience is light, edited on the web"), device.noteBodies())
            assertEquals(1, server.postBodies.size, "nothing was pushed again")
        }
    }

    /**
     * NOTE-18: two identical notes written in the same millisecond, then a lost response. The 1 ms nudge keeps their
     * `clientCreatedAt` apart, so each echo binds its own note and nothing is pushed twice.
     */
    @Test
    fun `two identical notes plus a lost response stay two notes`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.addNote("Written twice, word for word", "2:255", t(0))
            device.addNote("Written twice, word for word", "2:255", t(0))
            assertEquals(listOf(t(0), t(0) + 1), device.notes.notes().map { it.createdAt })
            server.loseNextPostResponse = true

            assertIs<SyncResult.Failed>(device.sync())
            device.settle()

            assertEquals(2, Remote(server).noteIds().size)
            assertEquals(2, device.noteBodies().size)
            assertEquals(1, server.postBodies.size, "nothing was pushed again")
        }
    }

    /**
     * Two notes with one identity (written past the repository's nudge, as old imported data could be) and a lost
     * response: each echo answers one create, so both bind and neither is pushed again.
     */
    @Test
    fun `a remote note answers one lost create only`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            repeat(2) { device.insertRawNote("Same note, same time", listOf(NoteRange(2, 255, 2, 255)), t(0)) }
            server.loseNextPostResponse = true

            assertIs<SyncResult.Failed>(device.sync())
            device.settle()

            assertEquals(2, Remote(server).noteIds().size)
            assertEquals(1, server.postBodies.size, "nothing was pushed again")
        }
    }

    /**
     * Two rows share `(clientCreatedAt, ranges)` (written past the nudge); the second's create is lost and its remote
     * copy deleted. It never takes the first row's remote note.
     */
    @Test
    fun `a lost note create never binds a remote note another row holds`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val synced = device.insertRawNote("Synced first", listOf(NoteRange(2, 255, 2, 255)), t(0))
            device.settle()
            val remote = Remote(server)
            val syncedId = remote.noteIds().single()
            device.insertRawNote("Written later, same time and verse", listOf(NoteRange(2, 255, 2, 255)), t(0))
            server.loseNextPostResponse = true
            assertIs<SyncResult.Failed>(device.sync())
            remote.deleteNote((remote.noteIds() - syncedId).single())
            remote.editNote(syncedId, "Synced first, edited on the web")

            device.settle()

            val bodies = setOf("Synced first, edited on the web", "Written later, same time and verse")
            assertEquals(bodies, remote.noteBodies().toSet())
            assertEquals(bodies, device.noteBodies().toSet())
            assertEquals("Synced first, edited on the web", device.notes.notes().single { it.localId == synced }.body)
        }
    }

    /**
     * A 500, then a repeat that commits but still answers 500. Anything posted next (a control POST, the other
     * half of a bisect) would hit OutOfSync; if it replaced the outbox of the batch that landed, the next GET
     * could not bind these notes and they would be created again.
     */
    @Test
    fun `a 500 after commit on the repeated batch never duplicates notes`() {
        listOf(true, false).forEach { withIndex ->
            FakeSyncServer().use { server ->
                server.includeMutationIndex = withIndex
                Device(server).use { device ->
                    repeat(4) { device.addNote("Note number $it", "1:${it + 1}", t(it)) }
                    // With an index: the first POST fails, the repeat commits and fails. Without one: both fail, and
                    // the first half of the bisect commits and fails.
                    server.rejectMutation = { if (server.postBodies.size <= (if (withIndex) 1 else 2)) HttpError(500, "ServerError", "x") else null }
                    server.failAfterCommitOfPost = 1

                    device.sync()
                    device.settle()

                    assertEquals(4, Remote(server).noteBodies().size, "index=$withIndex: ${server.postStatuses}")
                    assertEquals(4, device.noteBodies().size)
                }
            }
        }
    }

    /** Equal notes written on one device are separate notes. */
    @Test
    fun `two equal notes stay two notes`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.addNote("Same text", "1:1", t(0))
            device.addNote("Same text", "1:1", t(1))

            device.settle()

            assertEquals(listOf("Same text", "Same text"), Remote(server).noteBodies())
            assertEquals(listOf("Same text", "Same text"), device.noteBodies())
        }
    }

    /** No natural key, so no revival: deleting a note and writing the same text again is a new note. */
    @Test
    fun `a note deleted and written again is a new note`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val first = device.addNote("Same text", "1:1", t(0))
            device.settle()
            val firstId = Remote(server).noteIds().single()

            device.notes.deleteNote(first)
            device.addNote("Same text", "1:1", t(1))
            device.settle()

            assertEquals(listOf("Same text"), Remote(server).noteBodies())
            assertNotEquals(firstId, Remote(server).noteIds().single())
        }
    }

    /** NOTE-09: concurrent edits keep the newer one, whichever side it is on. */
    @Test
    fun `concurrent note edits keep the newer one`() {
        listOf(true, false).forEach { localNewer ->
            FakeSyncServer().use { server ->
                Device(server).use { device ->
                    val note = device.addNote("The first version", "2:255", t(0))
                    device.settle()
                    val remote = Remote(server)
                    remote.editNote(remote.noteIds().single(), "Edited on the web", updatedAt = if (localNewer) t(1) else t(3))
                    device.notes.updateNote(note, "Edited on the phone", t(2))

                    device.settle()

                    val expected = if (localNewer) "Edited on the phone" else "Edited on the web"
                    assertEquals(listOf(expected), device.noteBodies(), "localNewer=$localNewer")
                    assertEquals(listOf(expected), remote.noteBodies(), "localNewer=$localNewer")
                }
            }
        }
    }
}

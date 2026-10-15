package com.quran.shared.syncv2

import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.types.NoteRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * A note this device never synced that equals a new remote note (the same legacy note imported on two devices) is
 * linked to it: same body and ranges, whatever the creation times. Imports round the legacy creation time differently
 * (1 ms apart, or more), so the times can't be part of the match.
 */
class EqualNewNotesTest {

    @Test
    fun `a note imported a millisecond apart on two devices is linked, not duplicated`() = assertLinkedAcross(skew = -1)

    @Test
    fun `a note imported seconds apart on two devices is linked, not duplicated`() = assertLinkedAcross(skew = 7_000)

    /** The body is compared as the server keeps it (trimmed, escaped and read back), and the ranges as a set. */
    @Test
    fun `an untrimmed body with ranges in another order still links`() = FakeSyncServer().use { server ->
        Device(server).use { phone ->
            Device(server).use { tablet ->
                phone.notes.addNote("Tom & Jerry <3", listOf(AYAT_AL_KURSI, AL_IMRAN_18), IMPORTED)
                phone.settle()
                val local = tablet.insertRawNote("  Tom & Jerry <3\n", listOf(AL_IMRAN_18, AYAT_AL_KURSI), IMPORTED + 1)
                val posts = server.postBodies.size

                tablet.settle()

                assertEquals(1, Remote(server).noteIds().size, "${Remote(server).noteBodies()}")
                assertEquals(posts, server.postBodies.size, "the tablet pushed nothing")
                val note = tablet.notes.notes().single()
                assertEquals(local, note.localId)
                assertEquals("Tom & Jerry <3" to listOf(AYAT_AL_KURSI, AL_IMRAN_18), note.body to note.ranges, "the remote version")
            }
        }
    }

    /** Two copies on each device pair one to one: nothing is pushed and nothing is duplicated. */
    @Test
    fun `two identical notes on each device pair one to one`() = FakeSyncServer().use { server ->
        Device(server).use { phone ->
            Device(server).use { tablet ->
                repeat(2) { phone.addNote(BODY, "2:255", IMPORTED) }
                phone.settle()
                val locals = List(2) { tablet.addNote(BODY, "2:255", IMPORTED - 1) }
                val posts = server.postBodies.size

                tablet.settle()

                assertEquals(2, Remote(server).noteIds().size)
                assertEquals(posts, server.postBodies.size, "the tablet pushed nothing")
                assertEquals(locals.toSet(), tablet.notes.notes().map { it.localId }.toSet())
                assertEquals(setOf(IMPORTED, IMPORTED + 1), tablet.notes.notes().map { it.createdAt }.toSet())
            }
        }
    }

    /** Of several equal remote notes, the one created closest to the local note is the one it links to. */
    @Test
    fun `the remote note created closest is the one linked`() = FakeSyncServer().use { server ->
        Device(server).use { phone ->
            Device(server).use { tablet ->
                phone.addNote(BODY, "2:255", IMPORTED)
                phone.addNote(BODY, "2:255", IMPORTED + 10_000)
                phone.settle()
                val local = tablet.addNote(BODY, "2:255", IMPORTED + 9_000)
                val posts = server.postBodies.size

                tablet.settle()

                assertEquals(2, Remote(server).noteIds().size)
                assertEquals(posts, server.postBodies.size, "the tablet pushed nothing")
                assertEquals(IMPORTED + 10_000, tablet.notes.notes().single { it.localId == local }.createdAt)
                assertEquals(setOf(IMPORTED, IMPORTED + 10_000), tablet.notes.notes().map { it.createdAt }.toSet())
            }
        }
    }

    /**
     * An equal remote note archived on QuranReflect links too, and the local note takes the remote version: nothing the
     * user wrote is lost (the body and ranges are equal), and the note becomes archived here as it is there.
     */
    @Test
    fun `a note equal to one archived on QuranReflect is linked and becomes archived`() = FakeSyncServer().use { server ->
        Device(server).use { phone ->
            Device(server).use { tablet ->
                phone.addNote(BODY, "2:255", IMPORTED)
                phone.settle()
                val remote = Remote(server)
                remote.archiveNote(remote.noteIds().single())
                val local = tablet.addNote(BODY, "2:255", IMPORTED - 1)
                val posts = server.postBodies.size

                tablet.settle()

                assertEquals(remote.noteIds(), remote.archivedNoteIds(), "one note, still archived")
                assertEquals(1, remote.noteIds().size)
                assertEquals(posts, server.postBodies.size, "the tablet pushed nothing")
                val note = tablet.notes.notes().single()
                assertNotNull(note.archivedAt, "the remote's archive state")
                assertEquals(local, note.localId)
            }
        }
    }

    @Test
    fun `a never synced note with another body or other ranges is not linked`() {
        listOf(
            "$BODY!" to listOf(AYAT_AL_KURSI),
            BODY to listOf(NoteRange(2, 256, 2, 256)),
            BODY to listOf(AYAT_AL_KURSI, AL_IMRAN_18)
        ).forEach { (body, ranges) ->
            FakeSyncServer().use { server ->
                Device(server).use { phone ->
                    Device(server).use { tablet ->
                        phone.notes.addNote(BODY, listOf(AYAT_AL_KURSI), IMPORTED)
                        phone.settle()
                        tablet.notes.addNote(body, ranges, IMPORTED)

                        tablet.settle()

                        assertEquals(2, Remote(server).noteIds().size, "$body $ranges")
                        assertEquals(2, tablet.notes.notes().size, "$body $ranges")
                    }
                }
            }
        }
    }

    /** Only a note the server never saw links: a synced one is a note of its own, whatever another device adds. */
    @Test
    fun `a synced note is never linked to an equal new remote note`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.addNote(BODY, "2:255", IMPORTED)
            device.settle()
            Remote(server).createNote(BODY, "2:255-2:255", createdAt = IMPORTED + 1)

            device.settle()

            assertEquals(2, Remote(server).noteIds().size)
            assertEquals(listOf(BODY, BODY), device.noteBodies())
        }
    }

    /**
     * A lost CREATE is recognised by its exact `(clientCreatedAt, ranges)` only. A remote copy with another creation
     * time is not this device's push; with the body edited since, it is not an equal note either, so the note is
     * pushed again rather than bound to a note it can't be proven to be.
     */
    @Test
    fun `a lost note create is not resolved against a remote copy with a skewed creation time`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            val note = device.addNote(BODY, "2:255", IMPORTED)
            server.loseNextPostResponse = true
            assertIs<SyncResult.Failed>(device.sync())
            val notes = server.account(Device.ACCOUNT).notes
            val id = notes.keys.single()
            notes[id] = notes.getValue(id).copy(createdAt = Instant.fromEpochMilliseconds(IMPORTED + 1).toString())
            device.notes.updateNote(note, "$BODY, edited", IMPORTED + 5_000)

            device.settle()

            assertEquals(listOf("CREATE NOTE"), server.mutationTypesOfLastPost())
            assertEquals(setOf(BODY, "$BODY, edited"), Remote(server).noteBodies().toSet())
        }
    }

    /** The phone imports and syncs first; the tablet imports the same note [skew] ms apart, then signs in. */
    private fun assertLinkedAcross(skew: Long) = FakeSyncServer().use { server ->
        Device(server).use { phone ->
            Device(server).use { tablet ->
                phone.addNote(BODY, "2:255", IMPORTED)
                phone.settle()
                val local = tablet.addNote(BODY, "2:255", IMPORTED + skew)
                val posts = server.postBodies.size

                tablet.settle()

                assertEquals(1, Remote(server).noteIds().size, "skew $skew: ${Remote(server).noteBodies()}")
                assertEquals(posts, server.postBodies.size, "skew $skew: the tablet pushed nothing")
                val note = tablet.notes.notes().single()
                assertEquals(local, note.localId, "skew $skew: the tablet's note keeps its local id")
                assertEquals(IMPORTED, note.createdAt, "skew $skew: the remote version is kept")
            }
        }
    }

    /** A note written as is, past the repository's trimming (as older imported data could be). */
    private fun Device.insertRawNote(body: String, ranges: List<NoteRange>, createdAt: Long): Long {
        database.notesQueries.insert(body, createdAt, createdAt, null)
        val id = database.notesQueries.lastInsertId().executeAsOne()
        ranges.forEachIndexed { position, it ->
            database.notesQueries.insertRange(
                id, position.toLong(), it.startSura.toLong(), it.startAyah.toLong(), it.endSura.toLong(), it.endAyah.toLong()
            )
        }
        return id
    }

    private companion object {
        /**
         * A legacy note's creation time, ZCREATEDON 813529156.728795 (1_791_836_356_728.795 ms), as an import that
         * rounds it keeps it; one that floors it keeps …728 (`IMPORTED - 1`).
         */
        const val IMPORTED = 1_791_836_356_729L
        const val BODY = "Reflection on Ayat al-Kursi"
        val AYAT_AL_KURSI = NoteRange(2, 255, 2, 255)
        val AL_IMRAN_18 = NoteRange(3, 18, 3, 18)
    }
}

package com.quran.shared.syncv2

import com.quran.shared.syncv2.fake.FakeSyncServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Reading sessions: pruning, moves, edit-vs-delete. */
class ReadingSessionsSyncTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    /** Never-synced pruned sessions leave no tombstones, so nothing can be ambiguous. */
    @Test
    fun `two pruned unsynced sessions at one verse do not break the first sync`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            var n = 0
            device.sessions.addSession(18, 1, t(n++))
            repeat(20) { device.sessions.addSession(2, it + 1, t(n++)) }
            device.sessions.addSession(18, 1, t(n++))
            repeat(20) { device.sessions.addSession(3, it + 1, t(n++)) }
            Remote(server).createSession(18, 1, t(-100))

            device.syncOk()

            val newest = (1..20).map { "3:$it" }.toSet()
            assertEquals(newest, device.sessionVerses().toSet())
            assertEquals(newest, Remote(server).sessionVerses(), "the older remote 18:1 is pruned like any old session")
        }
    }

    /** A pruned never-synced session can't bind and delete another device's session. */
    @Test
    fun `a pruned unsynced session does not delete another device's session at that verse`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            var n = 0
            device.sessions.addSession(18, 1, t(n++))
            repeat(20) { device.sessions.addSession(2, it + 1, t(n++)) }
            Remote(server).createSession(18, 1, t(100))

            device.settle()

            assertTrue("18:1" in Remote(server).sessionVerses())
            assertTrue("18:1" in device.sessionVerses())
            assertEquals(20, device.sessionVerses().size)
            assertEquals(device.sessionVerses().toSet(), Remote(server).sessionVerses())
        }
    }

    /** Natural-key revival turns "pruned, then revisited" into one UPDATE instead of CREATE + DELETE. */
    @Test
    fun `revisiting a pruned synced session before sync keeps the visit`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            var n = 0
            device.sessions.addSession(2, 255, t(n++))
            device.settle()
            val remoteId = Remote(server).sessionIdAt(2, 255)
            repeat(20) { device.sessions.addSession(3, it + 1, t(n++)) }
            device.sessions.addSession(2, 255, t(n++))

            device.syncOk()

            assertTrue("2:255" in device.sessionVerses())
            assertEquals(remoteId, Remote(server).sessionIdAt(2, 255), "the same server session is updated")
            assertTrue("UPDATE READING_SESSION" in server.mutationTypesOfLastPost())
            assertFalse(server.postBodies.any { it.toString().contains("\"resourceId\":\"$remoteId\"") && it.toString().contains("DELETE") })
            assertEquals(device.sessionVerses().toSet(), Remote(server).sessionVerses())
        }
    }

    /** Edit beats delete, the other way: a local delete loses to a remote revisit. */
    @Test
    fun `a session deleted locally but revisited on another device comes back`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.sessions.addSession(2, 255, t(0))
            device.settle()
            val remote = Remote(server)
            remote.updateSession(remote.sessionIdAt(2, 255), 2, 255, t(10))
            device.sessions.deleteSession(2, 255)

            device.settle()

            assertEquals(listOf("2:255"), device.sessionVerses())
            assertEquals(setOf("2:255"), remote.sessionVerses())
        }
    }

    /** Field by field, sura would come from the local move and verse from the newer remote one: 2:7. */
    @Test
    fun `concurrent moves of one session converge on the newer verse, never a mix`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.sessions.addSession(1, 1, t(0))
            device.settle()
            val remote = Remote(server)
            remote.updateSession(remote.sessionIdAt(1, 1), 1, 7, t(20))
            device.sessions.moveSession(device.sessions.sessions().single().localId, 2, 2, t(10))

            device.settle()

            assertEquals(listOf("1:7"), device.sessionVerses())
            assertEquals(setOf("1:7"), remote.sessionVerses())
        }
    }

    /**
     * A local move onto a verse where another device just created a session: the newer of the two keeps the verse and
     * the older one is deleted, as the server does for a move. Reverting the move would leave both.
     */
    @Test
    fun `a local move clashing with a remote session keeps the newer one`() {
        listOf(30 to "the local move", 10 to "the remote session").forEach { (movedAt, winner) ->
            FakeSyncServer().use { server ->
                Device(server).use { device ->
                    device.sessions.addSession(1, 1, t(0))
                    device.settle()
                    val remote = Remote(server)
                    val moved = remote.sessionIdAt(1, 1)
                    device.sessions.moveSession(device.sessions.sessions().single().localId, 3, 3, t(movedAt))
                    val created = remote.createSession(3, 3, t(20))

                    device.settle()

                    assertEquals(listOf("3:3"), device.sessionVerses(), winner)
                    assertEquals(setOf("3:3"), remote.sessionVerses(), winner)
                    assertEquals(if (movedAt > 20) moved else created, remote.sessionIdAt(3, 3), "$winner keeps the verse")
                }
            }
        }
    }

    /**
     * The move wins the clash, but its POST never lands and the user moves again. The losing remote session is a
     * tombstone, so its own DELETE is pushed; without it, it would stay on the server and never reach this device.
     */
    @Test
    fun `a remote session that loses a clash is deleted even if the winning move never lands`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.sessions.addSession(1, 1, t(0))
            device.settle()
            val remote = Remote(server)
            val local = device.sessions.sessions().single().localId
            device.sessions.moveSession(local, 3, 3, t(30))
            remote.createSession(3, 3, t(20))
            server.dropNextPost = true

            assertIs<SyncResult.Failed>(device.sync())
            device.sessions.moveSession(local, 5, 5, t(40))
            device.settle()

            assertEquals(listOf("5:5"), device.sessionVerses())
            assertEquals(setOf("5:5"), remote.sessionVerses())
        }
    }

    /**
     * The losing remote session becomes a tombstone under local id -1; a remote edit restores it there; a remote
     * move then parks it, which must not land on a real verse.
     */
    @Test
    fun `a session restored under a negative id can be parked`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.sessions.addSession(2, 1, t(0))
            device.sessions.addSession(1, 1, t(1))
            device.settle()
            val remote = Remote(server)
            val local = device.sessions.sessions().single { it.sura == 1 }.localId
            val loser = remote.createSession(3, 3, t(20))
            device.sessions.moveSession(local, 3, 3, t(30))
            server.dropNextPost = true
            assertIs<SyncResult.Failed>(device.sync())
            assertEquals(setOf(-1L), device.engine.store.rows("READING_SESSION").keys.filter { it < 0 }.toSet())

            remote.updateSession(loser, 2, 5, t(40))
            device.syncOk()
            remote.updateSession(loser, 2, 9, t(50))
            device.settle()

            assertEquals(remote.sessionVerses(), device.sessionVerses().toSet())
        }
    }
}

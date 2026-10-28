package com.quran.shared.syncv2

import com.quran.shared.syncv2.fake.FakeSyncServer
import kotlin.test.Test
import kotlin.test.assertEquals

/** [SyncResult.pulled]: the local rows a run created, changed or deleted to take remote changes. */
class PulledCountTest {
    private fun t(n: Int) = 1_760_000_000_000L + n * 1_000L

    @Test
    fun `a run counts the remote creates, edits and deletes it took`() = FakeSyncServer().use { server ->
        Device(server).use { phone ->
            Device(server).use { tablet ->
                tablet.syncOk()
                val first = phone.addNote("The first note", "2:255", t(0))
                phone.addNote("The second note", "3:18", t(1))
                phone.collections.addCollection("Study", t(2))
                assertEquals(3, phone.syncOk().pushed)

                val created = tablet.syncOk()
                assertEquals(3 to 0, created.pulled to created.pushed, "two notes and a collection")
                assertEquals(0, tablet.syncOk().pulled, "nothing new")

                phone.notes.updateNote(first, "The first note, edited", t(3))
                phone.notes.deleteNote(phone.notes.notes().single { it.body == "The second note" }.localId)
                phone.syncOk()

                assertEquals(2, tablet.syncOk().pulled, "one edit, one delete")
            }
        }
    }

    @Test
    fun `a pull only run counts what it took`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            Remote(server).createNote("A note from the web", "1:1-1:1")

            val result = device.sync(push = false)

            assertEquals(1, (result as SyncResult.Success).pulled)
        }
    }

    @Test
    fun `a run's own pushes coming back are not pulled`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            device.addNote("A local note", "2:255", t(0))
            assertEquals(0, device.syncOk().pulled)
            assertEquals(0, device.syncOk().pulled, "the GET brings back this device's push")
        }
    }
}

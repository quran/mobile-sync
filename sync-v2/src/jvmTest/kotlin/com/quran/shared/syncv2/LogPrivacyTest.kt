package com.quran.shared.syncv2

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.quran.shared.syncv2.fake.FCollection
import com.quran.shared.syncv2.fake.FakeSyncServer
import com.quran.shared.syncv2.fake.HttpError
import com.quran.shared.syncv2.fake.str
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Logs reach the host app's sink, which may forward them to a crash reporter: they never carry what the user wrote
 * (note bodies, collection names) or what the server answered (its messages quote what was sent).
 */
class LogPrivacyTest {
    private val lines = mutableListOf<String>()
    private val writer = object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            // As the host's sink sees it: a logged error's stack trace (and its causes) follows the message.
            synchronized(lines) { lines += listOfNotNull(message, throwable?.stackTraceToString()).joinToString("\n") }
        }
    }

    @BeforeTest
    fun listen() = Logger.addLogWriter(writer)

    @AfterTest
    fun stop() = Logger.setLogWriters(Logger.config.logWriterList - writer)

    @Test
    fun `a GET response that is not valid JSON fails without quoting it`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            Remote(server).createNote("$SECRET about the verse", "2:255-2:255")
            server.truncateNextGetAfter = SECRET

            val failed = assertIs<SyncResult.Failed>(device.sync())

            assertTrue(failed.retryable)
            assertFalse(SECRET in failed.reason, failed.reason)
            assertFalse(generateSequence(failed.cause) { it.cause }.any { SECRET in it.toString() }, "${failed.cause}")
            assertNothingSecretLogged()
            assertTrue(lines.any { "Sync failed" in it }, "the failure is still logged: $lines")
        }
    }

    @Test
    fun `a rejected mutation is logged by status and code, without the server's message`() = FakeSyncServer().use { server ->
        server.rejectMutation = {
            if (it.str("resource") == "COLLECTION") HttpError(422, "ValidationError", "Collection '$SECRET' is invalid") else null
        }
        Device(server).use { device ->
            device.collections.addCollection(SECRET, 1_760_000_000_000L)

            assertEquals(listOf("422 ValidationError"), device.settle().held.map { it.reason })

            assertNothingSecretLogged()
            assertTrue(lines.any { "HTTP 422 ValidationError" in it }, "the rejection is still logged: $lines")
        }
    }

    @Test
    fun `a failed run's reason has no server message`() = FakeSyncServer().use { server ->
        // A lone 403 without an index may be the token's scope: the run fails. An empty code is the gateway's own
        // error, which has only a message.
        listOf(HttpError(403, "ForbiddenError", "No access to '$SECRET'"), HttpError(502, "", "Upstream said '$SECRET'"))
            .forEach { error ->
                server.rejectMutation = { error }
                Device(server).use { device ->
                    device.collections.addCollection(SECRET, 1_760_000_000_000L)

                    val failed = assertIs<SyncResult.Failed>(device.sync())

                    assertEquals(error.status, (failed.cause as SyncHttpException).status)
                    assertFalse(SECRET in failed.reason, failed.reason)
                }
            }
        assertNothingSecretLogged()
    }

    @Test
    fun `uniqueness repair logs ids, not collection names`() = FakeSyncServer().use { server ->
        Device(server).use { device ->
            // A local rename that clashes with a remote create is reverted.
            val study = device.collections.addCollection("Study", 1_760_000_000_000L).localId
            device.settle()
            device.collections.renameCollection(study, SECRET, 1_760_000_010_000L)
            Remote(server).createCollection(SECRET)
            device.settle()
            // Two names that differ only in case, from before the server checked it, are disambiguated.
            val account = server.account(Device.ACCOUNT)
            listOf("legacy-1" to "$SECRET 2", "legacy-2" to "${SECRET.uppercase()} 2").forEach { (id, name) ->
                account.collections[id] = FCollection(name, createdAt = account.now(), updatedAt = account.now())
                account.log("COLLECTION", id, "CREATE")
            }
            device.sync()

            assertEquals(4, device.collectionNames().size, "${device.collectionNames()}")
            assertTrue(lines.any { "reverting" in it } && lines.any { "disambiguating" in it }, "both repairs ran: $lines")
            assertNothingSecretLogged()
        }
    }

    private fun assertNothingSecretLogged() {
        val leaked = synchronized(lines) { lines.filter { SECRET.lowercase() in it.lowercase() } }
        assertEquals(emptyList(), leaked, "user text or a server message reached the logs")
    }

    private companion object {
        const val SECRET = "Private prayer for Sara"
    }
}

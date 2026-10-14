package com.quran.shared.pipeline.integration

import com.quran.shared.auth.model.AuthState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** The harness's less obvious paths. */
class SyncHarnessPathsTest {

    @Test
    fun `an expired session gives a skipped run that assertSucceeded rejects`() = syncScenario {
        val phone = device("phone")
        assertSucceeded(phone.login(ALICE))
        phone.auth.expireSession()

        val run = phone.syncNow()

        assertTrue(run.skipped)
        assertEquals(emptyList(), run.requests)
        assertFailsWith<SyncRunFailure> { assertSucceeded(run) }
        assertEquals(AuthState.Idle, phone.authService.authState.value)
    }

    @Test
    fun `a run the lifecycle gate skips fails fast with a clear message`() = syncScenario {
        val phone = device("phone")
        phone.login(ALICE)
        val hold = phone.auth.holdNextLogout()

        coroutineScope {
            val logout = async { phone.logout() }
            hold.reached.await()
            val started = System.nanoTime()
            val failure = assertFailsWith<HarnessTimeoutException> { phone.syncNow() }
            val elapsed = (System.nanoTime() - started).nanoseconds

            assertTrue("lifecycle gate" in failure.message.orEmpty(), failure.message)
            assertTrue(elapsed < SyncStatusRecorder.START_TIMEOUT + 2.seconds, "took $elapsed")
            hold.release.complete(Unit)
            logout.await()
        }
    }

    private companion object {
        const val ALICE = "alice"
    }
}

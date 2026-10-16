@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.pipeline

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How [SessionLifecycleCoordinator.withLogin] orders against writes and resets: writes don't wait for a
 * sign-in, while a reset still waits for one and a sign-in can't start during a reset.
 */
class SessionLifecycleCoordinatorLoginTest {
    @Test
    fun `writes run while a login waits for the user`() = runTest {
        val coordinator = SessionLifecycleCoordinator(virtualTimeStore())
        val loginStarted = CompletableDeferred<Unit>()
        val finishLogin = CompletableDeferred<Unit>()

        val login = async {
            coordinator.withLogin {
                loginStarted.complete(Unit)
                finishLogin.await()
                "signed in"
            }
        }
        loginStarted.await()

        assertEquals("written", coordinator.withMutatingWrite { "written" })
        assertEquals("imported", coordinator.withMutatingWrite { "imported" })
        assertTrue(login.isActive, "the login ended before the writes")

        finishLogin.complete(Unit)
        assertEquals("signed in", login.await())
    }

    @Test
    fun `writes still run one at a time while a login waits`() = runTest {
        val coordinator = SessionLifecycleCoordinator(virtualTimeStore())
        val loginStarted = CompletableDeferred<Unit>()
        val finishLogin = CompletableDeferred<Unit>()
        val firstWriteStarted = CompletableDeferred<Unit>()
        val finishFirstWrite = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val login = async {
            coordinator.withLogin {
                loginStarted.complete(Unit)
                finishLogin.await()
            }
        }
        loginStarted.await()
        val firstWrite = async {
            coordinator.withMutatingWrite {
                events += "first started"
                firstWriteStarted.complete(Unit)
                finishFirstWrite.await()
                events += "first finished"
            }
        }
        firstWriteStarted.await()
        val secondWrite = async {
            coordinator.withMutatingWrite {
                events += "second started"
            }
        }
        runCurrent()

        assertEquals(listOf("first started"), events, "the second write ran during the first")

        finishFirstWrite.complete(Unit)
        firstWrite.await()
        secondWrite.await()
        assertEquals(listOf("first started", "first finished", "second started"), events)

        finishLogin.complete(Unit)
        login.await()
    }

    @Test
    fun `reset waits for an in-flight login and the writes running beside it`() = runTest {
        val store = virtualTimeStore()
        val coordinator = SessionLifecycleCoordinator(store)
        val loginStarted = CompletableDeferred<Unit>()
        val finishLogin = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val resetRequested = CompletableDeferred<Unit>()
        val resetBlockStarted = CompletableDeferred<Unit>()

        val login = async {
            coordinator.withLogin {
                loginStarted.complete(Unit)
                finishLogin.await()
            }
        }
        loginStarted.await()
        val write = async {
            coordinator.withMutatingWrite {
                writeStarted.complete(Unit)
                finishWrite.await()
            }
        }
        writeStarted.await()

        val reset = async {
            coordinator.runManagedReset(beforeWriteDrain = { resetRequested.complete(Unit) }) {
                resetBlockStarted.complete(Unit)
            }
        }
        resetRequested.await()
        runCurrent()
        assertFalse(resetBlockStarted.isCompleted, "the reset ran during the login and the write")

        finishWrite.complete(Unit)
        write.await()
        runCurrent()
        assertFalse(resetBlockStarted.isCompleted, "the reset ran during the login")

        finishLogin.complete(Unit)
        login.await()
        reset.await()
        assertTrue(resetBlockStarted.isCompleted)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
    }

    @Test
    fun `persisted reset recovery waits for an in-flight login`() = runTest {
        val store = virtualTimeStore()
        val coordinator = SessionLifecycleCoordinator(store)
        val loginStarted = CompletableDeferred<Unit>()
        val finishLogin = CompletableDeferred<Unit>()
        var recoveryBlockRan = false

        val login = async {
            coordinator.withLogin {
                loginStarted.complete(Unit)
                finishLogin.await()
            }
        }
        loginStarted.await()
        store.beginReset()

        val recovery = async {
            coordinator.completePersistedResetIfNeeded {
                recoveryBlockRan = true
            }
        }
        runCurrent()
        assertFalse(recoveryBlockRan, "the recovery ran during the login")

        finishLogin.complete(Unit)
        login.await()
        recovery.await()
        assertTrue(recoveryBlockRan)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
    }

    @Test
    fun `login fails without starting while a reset runs or waits to run`() = runTest {
        val store = virtualTimeStore()
        val coordinator = SessionLifecycleCoordinator(store)
        val writeStarted = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val resetRequested = CompletableDeferred<Unit>()
        var loginsStarted = 0

        val write = async {
            coordinator.withMutatingWrite {
                writeStarted.complete(Unit)
                finishWrite.await()
            }
        }
        writeStarted.await()
        val reset = async {
            coordinator.runManagedReset(beforeWriteDrain = { resetRequested.complete(Unit) }) {
                assertFailsWith<SessionResetInProgressException> {
                    coordinator.withLogin { loginsStarted++ }
                }
            }
        }
        resetRequested.await()

        // The reset waits for the write; a login asked for now fails at once instead of queuing.
        val loginDuringDrain = async {
            assertFailsWith<SessionResetInProgressException> {
                coordinator.withLogin { loginsStarted++ }
            }
        }
        runCurrent()
        assertTrue(loginDuringDrain.isCompleted, "the login queued behind the reset")

        finishWrite.complete(Unit)
        write.await()
        reset.await()
        loginDuringDrain.await()

        assertEquals(0, loginsStarted)
        assertEquals("signed in", coordinator.withLogin { "signed in" })
    }

    @Test
    fun `cancelled reset waiting for a login lets later logins and writes run after recovery`() = runTest {
        val store = virtualTimeStore()
        val coordinator = SessionLifecycleCoordinator(store)
        val loginStarted = CompletableDeferred<Unit>()
        val finishLogin = CompletableDeferred<Unit>()
        val resetRequested = CompletableDeferred<Unit>()

        val login = async {
            coordinator.withLogin {
                loginStarted.complete(Unit)
                finishLogin.await()
            }
        }
        loginStarted.await()
        val reset = async {
            coordinator.runManagedReset(beforeWriteDrain = { resetRequested.complete(Unit) }) {}
        }
        resetRequested.await()
        runCurrent()

        reset.cancelAndJoin()
        finishLogin.complete(Unit)
        login.await()
        coordinator.completePersistedResetIfNeeded {}

        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
        assertEquals("signed in", coordinator.withLogin { "signed in" })
        assertEquals("allowed", coordinator.withMutatingWrite { "allowed" })
    }

    @Test
    fun `a login that throws lets a later reset and write run`() = runTest {
        val store = virtualTimeStore()
        val coordinator = SessionLifecycleCoordinator(store)

        assertFailsWith<IllegalStateException> {
            coordinator.withLogin { error("the browser sign-in failed") }
        }

        assertResetThenWriteRun(coordinator, store)
    }

    @Test
    fun `a cancelled login lets a later reset and write run`() = runTest {
        val store = virtualTimeStore()
        val coordinator = SessionLifecycleCoordinator(store)
        val loginStarted = CompletableDeferred<Unit>()

        val login = async {
            coordinator.withLogin {
                loginStarted.complete(Unit)
                awaitCancellation()
            }
        }
        loginStarted.await()
        login.cancelAndJoin()

        assertResetThenWriteRun(coordinator, store)
    }

    @Test
    fun `a write that throws lets a later reset and write run`() = runTest {
        val store = virtualTimeStore()
        val coordinator = SessionLifecycleCoordinator(store)

        assertFailsWith<IllegalStateException> {
            coordinator.withMutatingWrite { error("the write failed") }
        }

        assertResetThenWriteRun(coordinator, store)
    }

    /**
     * Settings on the test scheduler, so [runCurrent] runs every coroutine as far as it can go: one still
     * running afterwards is held at the gate, not on a settings thread. Every test uses it.
     */
    private fun TestScope.virtualTimeStore() =
        SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings(StandardTestDispatcher(testScheduler)))

    /** A reset that found the gate still held would wait forever, so it must finish without time passing. */
    private suspend fun TestScope.assertResetThenWriteRun(
        coordinator: SessionLifecycleCoordinator,
        store: SessionLifecycleStateStore
    ) {
        var resetBlockRan = false
        val reset = async { coordinator.runManagedReset { resetBlockRan = true } }
        runCurrent()

        assertTrue(reset.isCompleted, "the reset waited on a gate nothing holds")
        reset.await()
        assertTrue(resetBlockRan)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
        assertEquals("written", coordinator.withMutatingWrite { "written" })
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.quran.shared.pipeline

import com.quran.shared.syncengine.SyncOperationInvalidatedException
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionLifecycleCoordinatorTest {

    @Test
    fun `reset marker and epoch are persisted before reset work`() = runTest {
        val store = SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        val coordinator = SessionLifecycleCoordinator(store)
        val statesDuringReset = mutableListOf<SessionLifecycleState>()

        coordinator.runManagedReset {
            statesDuringReset += store.snapshot()
        }

        assertEquals(listOf(SessionLifecycleState(epoch = 1L, resetInProgress = true)), statesDuringReset)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
    }

    @Test
    fun `reset failure leaves marker active and blocks writes until recovery succeeds`() = runTest {
        val store = SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        val coordinator = SessionLifecycleCoordinator(store)

        assertFailsWith<IllegalStateException> {
            coordinator.runManagedReset {
                throw IllegalStateException("delete failed")
            }
        }

        assertEquals(true, store.snapshot().resetInProgress)
        assertFailsWith<SessionResetInProgressException> {
            coordinator.withMutatingWrite { "blocked" }
        }

        var recovered = false
        coordinator.completePersistedResetIfNeeded {
            recovered = true
        }

        assertEquals(true, recovered)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
        assertEquals("allowed", coordinator.withMutatingWrite { "allowed" })
    }

    @Test
    fun `recovery advances epoch before clearing marker persisted at old epoch`() = runTest {
        val settings = MapSettings().toSuspendSettings()
        settings.putBoolean("com.quran.sync.reset_in_progress", true)
        val store = SettingsSessionLifecycleStateStore(settings)
        val coordinator = SessionLifecycleCoordinator(store)
        var recovered = false

        coordinator.completePersistedResetIfNeeded {
            recovered = true
            assertFailsWith<SyncOperationInvalidatedException> {
                coordinator.checkSyncEpoch(0L)
            }
        }

        assertEquals(true, recovered)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
        assertFailsWith<SyncOperationInvalidatedException> {
            coordinator.checkSyncEpoch(0L)
        }
    }

    @Test
    fun `reset failure releases local owner when follow-up snapshot fails`() = runTest {
        val store = FailingSnapshotAfterResetFailureStore()
        val coordinator = SessionLifecycleCoordinator(store)

        assertFailsWith<IllegalStateException> {
            coordinator.runManagedReset {
                store.failSnapshots = true
                throw IllegalStateException("local reset failed")
            }
        }

        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = true), store.currentState())

        store.failSnapshots = false
        var recovered = false
        coordinator.completePersistedResetIfNeeded {
            recovered = true
        }

        assertEquals(true, recovered)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.currentState())
        assertEquals("allowed", coordinator.withMutatingWrite { "allowed" })
    }

    @Test
    fun `fresh coordinator allows immediate clean mutating write before startup recovery load`() = runTest {
        val store = SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        val coordinator = SessionLifecycleCoordinator(store)

        assertEquals("allowed", coordinator.withMutatingWrite { "allowed" })
    }

    @Test
    fun `fresh coordinator blocks immediate mutating write when persisted reset marker is active`() = runTest {
        val store = SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        store.beginReset()
        val coordinator = SessionLifecycleCoordinator(store)

        assertFailsWith<SessionResetInProgressException> {
            coordinator.withMutatingWrite { "blocked" }
        }
    }

    @Test
    fun `mutating write after reset starts does not queue behind persisted begin reset`() = runTest {
        val store = BlockingBeginResetStore()
        val coordinator = SessionLifecycleCoordinator(store)
        var resetBlockRan = false
        var writeBlockRan = false

        val resetJob = async {
            coordinator.runManagedReset {
                resetBlockRan = true
            }
        }
        store.beginResetStarted.await()

        val writeJob = async {
            assertFailsWith<SessionResetInProgressException> {
                coordinator.withMutatingWrite {
                    writeBlockRan = true
                }
            }
        }
        runCurrent()

        assertTrue(writeJob.isCompleted, "Write submitted during reset startup should fail without queuing")
        assertEquals(false, writeBlockRan)

        store.allowBeginReset.complete(Unit)
        resetJob.await()
        writeJob.await()

        assertEquals(true, resetBlockRan)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
    }

    @Test
    fun `mutating write submitted after reset request fails while reset waits for active write`() = runTest {
        val store = SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        val coordinator = SessionLifecycleCoordinator(store)
        val activeWriteStarted = CompletableDeferred<Unit>()
        val allowActiveWriteToFinish = CompletableDeferred<Unit>()
        val resetRequestStarted = CompletableDeferred<Unit>()
        val resetBlockStarted = CompletableDeferred<Unit>()
        var secondWriteRan = false

        val activeWrite = async {
            coordinator.withMutatingWrite {
                activeWriteStarted.complete(Unit)
                allowActiveWriteToFinish.await()
                "active"
            }
        }
        activeWriteStarted.await()

        val resetJob = async {
            coordinator.runManagedReset(
                beforeWriteDrain = { resetRequestStarted.complete(Unit) }
            ) {
                resetBlockStarted.complete(Unit)
            }
        }
        resetRequestStarted.await()

        val secondWrite = async {
            assertFailsWith<SessionResetInProgressException> {
                coordinator.withMutatingWrite {
                    secondWriteRan = true
                }
            }
        }
        runCurrent()

        assertTrue(secondWrite.isCompleted, "Write submitted after reset request should fail without queuing")
        assertEquals(false, secondWriteRan)
        assertEquals(false, resetBlockStarted.isCompleted)
        assertEquals(true, store.snapshot().resetInProgress)

        allowActiveWriteToFinish.complete(Unit)

        assertEquals("active", activeWrite.await())
        resetJob.await()
        secondWrite.await()

        assertEquals(true, resetBlockStarted.isCompleted)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
    }

    @Test
    fun `cancelled reset waiting for active write releases local owner for persisted recovery`() = runTest {
        val store = SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        val coordinator = SessionLifecycleCoordinator(store)
        val activeWriteStarted = CompletableDeferred<Unit>()
        val allowActiveWriteToFinish = CompletableDeferred<Unit>()
        val resetRequestStarted = CompletableDeferred<Unit>()
        val resetBlockStarted = CompletableDeferred<Unit>()
        var recoveryBlockRan = false

        val activeWrite = async {
            coordinator.withMutatingWrite {
                activeWriteStarted.complete(Unit)
                allowActiveWriteToFinish.await()
                "active"
            }
        }
        activeWriteStarted.await()

        val reset = async {
            coordinator.runManagedReset(
                beforeWriteDrain = { resetRequestStarted.complete(Unit) }
            ) {
                resetBlockStarted.complete(Unit)
            }
        }
        resetRequestStarted.await()

        assertEquals(true, store.snapshot().resetInProgress)
        assertEquals(false, resetBlockStarted.isCompleted)

        reset.cancelAndJoin()

        assertEquals(true, store.snapshot().resetInProgress)
        assertEquals(false, resetBlockStarted.isCompleted)

        allowActiveWriteToFinish.complete(Unit)
        assertEquals("active", activeWrite.await())

        coordinator.completePersistedResetIfNeeded {
            recoveryBlockRan = true
        }

        assertEquals(true, recoveryBlockRan)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
        assertEquals("allowed", coordinator.withMutatingWrite { "allowed" })
    }

    @Test
    fun `begin reset persistence failure clears unpersisted in-memory reset marker`() = runTest {
        val store = FailingBeginResetStore()
        val coordinator = SessionLifecycleCoordinator(store)

        assertFailsWith<IllegalStateException> {
            coordinator.runManagedReset {}
        }

        assertEquals(SessionLifecycleState(epoch = 0L, resetInProgress = false), store.snapshot())
        assertEquals("allowed", coordinator.withMutatingWrite { "allowed" })
    }

    @Test
    fun `begin reset failure releases local owner when cleanup snapshot also fails`() = runTest {
        val store = PartiallyPersistingFailingBeginResetStore()
        val coordinator = SessionLifecycleCoordinator(store)

        assertFailsWith<IllegalStateException> {
            coordinator.runManagedReset {}
        }

        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = true), store.currentState())

        store.failSnapshots = false
        var recovered = false
        coordinator.completePersistedResetIfNeeded {
            recovered = true
        }

        assertEquals(true, recovered)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.currentState())
        assertEquals("allowed", coordinator.withMutatingWrite { "allowed" })
    }

    @Test
    fun `sync epoch checks reject stale work after reset begins`() = runTest {
        val store = SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        val coordinator = SessionLifecycleCoordinator(store)
        coordinator.completePersistedResetIfNeeded {}

        val epoch = coordinator.captureSyncEpoch()

        assertFailsWith<SyncOperationInvalidatedException> {
            coordinator.runManagedReset {
                coordinator.checkSyncEpoch(epoch)
            }
        }
        assertFailsWith<SyncOperationInvalidatedException> {
            coordinator.checkSyncEpoch(epoch)
        }
    }

    @Test
    fun `post admission rejects sync epoch when reset wins before admission`() = runTest {
        val store = BlockingSecondSnapshotStore()
        val coordinator = SessionLifecycleCoordinator(store)
        val epoch = coordinator.captureSyncEpoch()
        val resetCanFinish = CompletableDeferred<Unit>()
        var admissionSucceeded = false

        val admission = async {
            assertFailsWith<SyncOperationInvalidatedException> {
                coordinator.admitSyncPost(epoch)
                admissionSucceeded = true
            }
        }
        store.secondSnapshotStarted.await()

        val reset = async {
            coordinator.runManagedReset {
                resetCanFinish.await()
            }
        }
        runCurrent()

        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = true), store.currentState())

        store.allowSecondSnapshot.complete(Unit)
        resetCanFinish.complete(Unit)

        admission.await()
        reset.await()

        assertEquals(false, admissionSucceeded)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.currentState())
    }

    @Test
    fun `reset request latches while epoch gated completion is suspended`() = runTest {
        val store = SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        val coordinator = SessionLifecycleCoordinator(store)
        val epoch = coordinator.captureSyncEpoch()
        val completionStarted = CompletableDeferred<Unit>()
        val completionCanFinish = CompletableDeferred<Unit>()
        val resetRequestStarted = CompletableDeferred<Unit>()
        val resetCanFinish = CompletableDeferred<Unit>()
        var writeBlockRan = false

        val completion = async {
            coordinator.withValidSyncEpoch(epoch) {
                completionStarted.complete(Unit)
                completionCanFinish.await()
            }
        }
        completionStarted.await()

        val reset = async {
            coordinator.runManagedReset(
                beforeWriteDrain = { resetRequestStarted.complete(Unit) }
            ) {
                resetCanFinish.await()
            }
        }
        resetRequestStarted.await()

        val write = async {
            assertFailsWith<SessionResetInProgressException> {
                coordinator.withMutatingWrite {
                    writeBlockRan = true
                }
            }
        }
        runCurrent()

        assertEquals(true, store.snapshot().resetInProgress)
        assertTrue(write.isCompleted, "Write after reset request should fail without waiting for sync completion")
        assertEquals(false, writeBlockRan)

        completionCanFinish.complete(Unit)
        resetCanFinish.complete(Unit)

        completion.await()
        reset.await()
        write.await()

        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
    }

    @Test
    fun `reset request latches while epoch gate is reading persisted state`() = runTest {
        val store = BlockingSecondSnapshotStore()
        val coordinator = SessionLifecycleCoordinator(store)
        val epoch = coordinator.captureSyncEpoch()
        val resetCanFinish = CompletableDeferred<Unit>()
        var completionBlockRan = false
        var writeBlockRan = false

        val completion = async {
            assertFailsWith<SyncOperationInvalidatedException> {
                coordinator.withValidSyncEpoch(epoch) {
                    completionBlockRan = true
                }
            }
        }
        store.secondSnapshotStarted.await()

        val reset = async {
            coordinator.runManagedReset {
                resetCanFinish.await()
            }
        }
        runCurrent()

        val write = async {
            assertFailsWith<SessionResetInProgressException> {
                coordinator.withMutatingWrite {
                    writeBlockRan = true
                }
            }
        }
        runCurrent()

        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = true), store.currentState())
        assertTrue(write.isCompleted, "Write after reset request should fail without waiting for state read")
        assertEquals(false, writeBlockRan)

        store.allowSecondSnapshot.complete(Unit)
        resetCanFinish.complete(Unit)

        completion.await()
        reset.await()
        write.await()

        assertEquals(false, completionBlockRan)
        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.currentState())
    }

    @Test
    fun `persisted reset recovery ignores locally started reset while it waits for writes`() = runTest {
        val store = SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        val coordinator = SessionLifecycleCoordinator(store)
        val activeWriteStarted = CompletableDeferred<Unit>()
        val activeWriteCanFinish = CompletableDeferred<Unit>()
        val resetRequestStarted = CompletableDeferred<Unit>()
        val resetCanFinish = CompletableDeferred<Unit>()
        var recoveryBlockRan = false

        val activeWrite = async {
            coordinator.withMutatingWrite {
                activeWriteStarted.complete(Unit)
                activeWriteCanFinish.await()
            }
        }
        activeWriteStarted.await()

        val reset = async {
            coordinator.runManagedReset(
                beforeWriteDrain = { resetRequestStarted.complete(Unit) }
            ) {
                resetCanFinish.await()
            }
        }
        resetRequestStarted.await()

        assertEquals(true, store.snapshot().resetInProgress)

        coordinator.completePersistedResetIfNeeded {
            recoveryBlockRan = true
        }

        assertEquals(false, recoveryBlockRan)
        assertEquals(true, store.snapshot().resetInProgress)

        activeWriteCanFinish.complete(Unit)
        resetCanFinish.complete(Unit)

        activeWrite.await()
        reset.await()

        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = false), store.snapshot())
    }

    @Test
    fun `older managed reset completion cannot clear newer reset request`() = runTest {
        val store = BlockingStaleFinishResetStore()
        val coordinator = SessionLifecycleCoordinator(store)
        val resetAStarted = CompletableDeferred<Unit>()
        val resetACanFinish = CompletableDeferred<Unit>()
        val resetBCanFinish = CompletableDeferred<Unit>()
        var writeDuringResetRan = false

        val resetA = async {
            coordinator.runManagedReset {
                resetAStarted.complete(Unit)
                resetACanFinish.await()
            }
        }
        resetAStarted.await()

        resetACanFinish.complete(Unit)
        store.finishResetStarted.await()

        val resetB = async {
            coordinator.runManagedReset {
                resetBCanFinish.await()
            }
        }
        runCurrent()

        assertEquals(SessionLifecycleState(epoch = 1L, resetInProgress = true), store.snapshot())

        store.allowFinishReset.complete(Unit)
        resetA.await()
        runCurrent()

        assertEquals(SessionLifecycleState(epoch = 2L, resetInProgress = true), store.snapshot())
        assertFailsWith<SessionResetInProgressException> {
            coordinator.withMutatingWrite {
                writeDuringResetRan = true
            }
        }
        assertEquals(false, writeDuringResetRan)

        resetBCanFinish.complete(Unit)
        resetB.await()

        assertEquals(SessionLifecycleState(epoch = 2L, resetInProgress = false), store.snapshot())
        assertEquals("allowed", coordinator.withMutatingWrite { "allowed" })
    }

    @Test
    fun `persisted reset recovery does not finish live reset that starts while recovery waits`() = runTest {
        val store = SettingsSessionLifecycleStateStore(MapSettings().toSuspendSettings())
        val coordinator = SessionLifecycleCoordinator(store)
        val activeWriteStarted = CompletableDeferred<Unit>()
        val activeWriteCanFinish = CompletableDeferred<Unit>()
        val liveResetCanFinish = CompletableDeferred<Unit>()
        var recoveryBlockRan = false

        val activeWrite = async {
            coordinator.withMutatingWrite {
                activeWriteStarted.complete(Unit)
                activeWriteCanFinish.await()
            }
        }
        activeWriteStarted.await()

        store.beginReset()
        val recovery = async {
            coordinator.completePersistedResetIfNeeded {
                recoveryBlockRan = true
            }
        }
        runCurrent()

        val liveReset = async {
            coordinator.runManagedReset {
                liveResetCanFinish.await()
            }
        }
        while (store.snapshot().epoch < 2L) {
            runCurrent()
            yield()
        }

        activeWriteCanFinish.complete(Unit)
        activeWrite.await()
        recovery.await()

        assertEquals(false, recoveryBlockRan)
        assertEquals(SessionLifecycleState(epoch = 2L, resetInProgress = true), store.snapshot())

        liveResetCanFinish.complete(Unit)
        liveReset.await()

        assertEquals(SessionLifecycleState(epoch = 2L, resetInProgress = false), store.snapshot())
    }
}

private class BlockingBeginResetStore : SessionLifecycleStateStore {
    val beginResetStarted = CompletableDeferred<Unit>()
    val allowBeginReset = CompletableDeferred<Unit>()
    private var state = SessionLifecycleState(epoch = 0L, resetInProgress = false)

    override suspend fun snapshot(): SessionLifecycleState = state

    override suspend fun beginReset(): SessionLifecycleState {
        beginResetStarted.complete(Unit)
        allowBeginReset.await()
        state = SessionLifecycleState(epoch = state.epoch + 1, resetInProgress = true)
        return state
    }

    override suspend fun finishReset(expectedEpoch: Long): SessionLifecycleState {
        if (state.epoch == expectedEpoch) {
            state = state.copy(resetInProgress = false)
        }
        return state
    }
}

private class FailingBeginResetStore : SessionLifecycleStateStore {
    private var state = SessionLifecycleState(epoch = 0L, resetInProgress = false)

    override suspend fun snapshot(): SessionLifecycleState = state

    override suspend fun beginReset(): SessionLifecycleState {
        throw IllegalStateException("begin reset failed")
    }

    override suspend fun finishReset(expectedEpoch: Long): SessionLifecycleState {
        if (state.epoch == expectedEpoch) {
            state = state.copy(resetInProgress = false)
        }
        return state
    }
}

private class PartiallyPersistingFailingBeginResetStore : SessionLifecycleStateStore {
    var failSnapshots = true
    private var state = SessionLifecycleState(epoch = 0L, resetInProgress = false)

    override suspend fun snapshot(): SessionLifecycleState {
        if (failSnapshots) {
            throw IllegalStateException("snapshot failed")
        }
        return state
    }

    override suspend fun beginReset(): SessionLifecycleState {
        state = SessionLifecycleState(epoch = state.epoch + 1, resetInProgress = true)
        throw IllegalStateException("begin reset failed after marker persisted")
    }

    override suspend fun finishReset(expectedEpoch: Long): SessionLifecycleState {
        if (state.epoch == expectedEpoch) {
            state = state.copy(resetInProgress = false)
        }
        return state
    }

    fun currentState(): SessionLifecycleState = state
}

private class FailingSnapshotAfterResetFailureStore : SessionLifecycleStateStore {
    var failSnapshots = false
    private var state = SessionLifecycleState(epoch = 0L, resetInProgress = false)

    override suspend fun snapshot(): SessionLifecycleState {
        if (failSnapshots) {
            throw IllegalStateException("snapshot failed")
        }
        return state
    }

    override suspend fun beginReset(): SessionLifecycleState {
        state = SessionLifecycleState(epoch = state.epoch + 1, resetInProgress = true)
        return state
    }

    override suspend fun finishReset(expectedEpoch: Long): SessionLifecycleState {
        if (state.epoch == expectedEpoch) {
            state = state.copy(resetInProgress = false)
        }
        return state
    }

    fun currentState(): SessionLifecycleState = state
}

private class BlockingSecondSnapshotStore : SessionLifecycleStateStore {
    val secondSnapshotStarted = CompletableDeferred<Unit>()
    val allowSecondSnapshot = CompletableDeferred<Unit>()
    private var snapshotCount = 0
    private var state = SessionLifecycleState(epoch = 0L, resetInProgress = false)

    override suspend fun snapshot(): SessionLifecycleState {
        snapshotCount += 1
        if (snapshotCount == 2) {
            secondSnapshotStarted.complete(Unit)
            allowSecondSnapshot.await()
        }
        return state
    }

    override suspend fun beginReset(): SessionLifecycleState {
        state = SessionLifecycleState(epoch = state.epoch + 1, resetInProgress = true)
        return state
    }

    override suspend fun finishReset(expectedEpoch: Long): SessionLifecycleState {
        if (state.epoch == expectedEpoch) {
            state = state.copy(resetInProgress = false)
        }
        return state
    }

    fun currentState(): SessionLifecycleState = state
}

private class BlockingStaleFinishResetStore : SessionLifecycleStateStore {
    val finishResetStarted = CompletableDeferred<Unit>()
    val allowFinishReset = CompletableDeferred<Unit>()
    private var state = SessionLifecycleState(epoch = 0L, resetInProgress = false)
    private var finishCount = 0

    override suspend fun snapshot(): SessionLifecycleState = state

    override suspend fun beginReset(): SessionLifecycleState {
        state = SessionLifecycleState(epoch = state.epoch + 1, resetInProgress = true)
        return state
    }

    override suspend fun finishReset(expectedEpoch: Long): SessionLifecycleState {
        val epochMatchedBeforeSuspension = state.epoch == expectedEpoch
        finishCount += 1
        if (finishCount == 1) {
            finishResetStarted.complete(Unit)
            allowFinishReset.await()
        }
        if (epochMatchedBeforeSuspension) {
            state = state.copy(resetInProgress = false)
        }
        return state
    }
}

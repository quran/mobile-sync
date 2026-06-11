package com.quran.shared.pipeline

import com.quran.shared.di.AppScope
import com.quran.shared.syncengine.SyncLifecycleGate
import com.quran.shared.syncengine.SyncOperationInvalidatedException
import com.russhwolf.settings.coroutines.SuspendSettings
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

class SessionResetInProgressException : IllegalStateException("Session reset in progress")

data class SessionLifecycleState(
    val epoch: Long,
    val resetInProgress: Boolean
)

@HiddenFromObjC
interface SessionLifecycleStateStore {
    suspend fun snapshot(): SessionLifecycleState
    suspend fun beginReset(): SessionLifecycleState
    suspend fun ensureResetEpochAdvancedForRecovery(state: SessionLifecycleState): SessionLifecycleState = state
    suspend fun finishReset(expectedEpoch: Long): SessionLifecycleState
}

@HiddenFromObjC
@SingleIn(AppScope::class)
class SettingsSessionLifecycleStateStore @Inject constructor(
    private val settings: SuspendSettings
) : SessionLifecycleStateStore {

    override suspend fun snapshot(): SessionLifecycleState =
        SessionLifecycleState(
            epoch = settings.getLong(KEY_EPOCH, 0L),
            resetInProgress = settings.getBoolean(KEY_RESET_IN_PROGRESS, false)
        )

    override suspend fun beginReset(): SessionLifecycleState {
        settings.putBoolean(KEY_RESET_IN_PROGRESS, true)
        val nextEpoch = settings.getLong(KEY_EPOCH, 0L) + 1
        settings.putLong(KEY_EPOCH, nextEpoch)
        settings.putLong(KEY_RESET_EPOCH_CONFIRMED, nextEpoch)
        return SessionLifecycleState(epoch = nextEpoch, resetInProgress = true)
    }

    override suspend fun ensureResetEpochAdvancedForRecovery(
        state: SessionLifecycleState
    ): SessionLifecycleState {
        val currentState = snapshot()
        if (!currentState.resetInProgress) {
            return currentState
        }
        val confirmedResetEpoch = settings.getLongOrNull(KEY_RESET_EPOCH_CONFIRMED)
        return if (confirmedResetEpoch == currentState.epoch) {
            currentState
        } else {
            beginReset()
        }
    }

    override suspend fun finishReset(expectedEpoch: Long): SessionLifecycleState {
        if (
            settings.getLong(KEY_EPOCH, 0L) == expectedEpoch &&
            settings.getLongOrNull(KEY_RESET_EPOCH_CONFIRMED) == expectedEpoch
        ) {
            settings.putBoolean(KEY_RESET_IN_PROGRESS, false)
        }
        return snapshot()
    }

    private companion object {
        const val KEY_EPOCH = "com.quran.sync.session_epoch"
        const val KEY_RESET_IN_PROGRESS = "com.quran.sync.reset_in_progress"
        const val KEY_RESET_EPOCH_CONFIRMED = "com.quran.sync.reset_epoch_confirmed"
    }
}

@HiddenFromObjC
@SingleIn(AppScope::class)
class SessionLifecycleCoordinator @Inject constructor(
    private val stateStore: SessionLifecycleStateStore
) : SyncLifecycleGate {
    private val stateMutex = Mutex()
    private val writeResetMutex = Mutex()
    private val syncEpochAdmissionMutex = Mutex()
    private val resetTransitionMutex = Mutex()

    @Volatile
    private var cachedState: SessionLifecycleState? = null
    @Volatile
    private var resetStartedInMemory = false
    @Volatile
    private var resetStartedLocally = false
    private var nextResetOwnerId = 0L
    private var activeResetOwner: ResetOwner? = null

    override fun canStartSync(): Boolean = !resetStartedInMemory && cachedState?.resetInProgress == false

    override suspend fun captureSyncEpoch(): Long {
        val state = readState()
        if (state.resetInProgress) {
            throw SyncOperationInvalidatedException("Session reset is in progress")
        }
        return state.epoch
    }

    override suspend fun checkSyncEpoch(epoch: Long) {
        val state = readState()
        requireSyncEpoch(epoch, state)
    }

    override suspend fun admitSyncPost(epoch: Long) {
        val state = readState()
        syncEpochAdmissionMutex.withLock {
            requireSyncEpoch(epoch, currentCachedStateOr(state))
        }
    }

    override suspend fun <T> withValidSyncEpoch(epoch: Long, block: suspend () -> T): T {
        val state = readState()
        syncEpochAdmissionMutex.withLock {
            requireSyncEpoch(epoch, currentCachedStateOr(state))
        }
        return block()
    }

    suspend fun <T> withMutatingWrite(block: suspend () -> T): T {
        if (isResetInProgress()) {
            throw SessionResetInProgressException()
        }
        return writeResetMutex.withLock {
            if (readState().resetInProgress) {
                throw SessionResetInProgressException()
            }
            block()
        }
    }

    suspend fun <T> runManagedReset(
        beforeWriteDrain: suspend () -> Unit = {},
        block: suspend () -> T
    ): T {
        var owner: ResetOwner? = null
        var resetFinished = false
        var lockAcquired = false
        try {
            val resetOwner = beginReset()
            owner = resetOwner
            beforeWriteDrain()
            writeResetMutex.lock()
            lockAcquired = true
            val result = block()
            finishReset(resetOwner)
            resetFinished = true
            return result
        } finally {
            if (lockAcquired) {
                writeResetMutex.unlock()
            }
            if (!resetFinished) {
                withContext(NonCancellable) {
                    owner?.let { refreshCachedStateAfterResetFailure(it) }
                }
            }
        }
    }

    suspend fun completePersistedResetIfNeeded(block: suspend () -> Unit) {
        val recoveryState = capturePersistedResetForRecovery() ?: return
        writeResetMutex.withLock {
            val currentState = capturePersistedResetForRecovery() ?: return
            if (currentState.epoch != recoveryState.epoch) {
                return
            }
            val resetState = ensurePersistedResetEpochAdvancedForRecovery(currentState) ?: return
            try {
                block()
                finishPersistedReset(resetState.epoch)
            } catch (exception: Throwable) {
                refreshCachedState()
                throw exception
            }
        }
    }

    private suspend fun capturePersistedResetForRecovery(): SessionLifecycleState? {
        if (resetStartedLocally) {
            return null
        }
        val persistedState = resetTransitionMutex.withLock {
            stateStore.snapshot()
        }
        if (resetStartedLocally) {
            return null
        }
        updateCachedState(persistedState)
        if (resetStartedLocally || !persistedState.resetInProgress) {
            return null
        }
        return persistedState
    }

    private suspend fun ensurePersistedResetEpochAdvancedForRecovery(
        expectedState: SessionLifecycleState
    ): SessionLifecycleState? {
        val resetState = resetTransitionMutex.withLock {
            val latestState = stateStore.snapshot()
            if (!latestState.resetInProgress || latestState.epoch != expectedState.epoch) {
                null
            } else {
                stateStore.ensureResetEpochAdvancedForRecovery(latestState)
            }
        } ?: return null
        updateCachedState(resetState)
        return if (resetState.resetInProgress) resetState else null
    }

    private suspend fun beginReset(): ResetOwner {
        val ownerId = syncEpochAdmissionMutex.withLock {
            stateMutex.withLock {
                markResetStartedInMemoryLocked()
            }
        }
        try {
            val persistedState = withContext(NonCancellable) {
                resetTransitionMutex.withLock {
                    stateStore.beginReset()
                }
            }
            return stateMutex.withLock {
                val owner = ResetOwner(ownerId, persistedState.epoch)
                if (activeResetOwner?.id == ownerId) {
                    activeResetOwner = owner
                    updateCachedStateLocked(persistedState)
                }
                owner
            }
        } catch (exception: Throwable) {
            withContext(NonCancellable) {
                clearFailedResetStart(ownerId)
            }
            throw exception
        }
    }

    private suspend fun finishReset(owner: ResetOwner) {
        val persistedState = resetTransitionMutex.withLock {
            if (!activeResetOwnerMatches(owner)) {
                null
            } else {
                stateStore.finishReset(requireNotNull(owner.epoch))
            }
        } ?: return
        stateMutex.withLock {
            if (activeResetOwner?.id == owner.id) {
                activeResetOwner = null
                updateCachedStateLocked(persistedState, clearLocalResetStart = true)
            } else {
                updateCachedStateRespectingActiveResetLocked(persistedState)
            }
        }
    }

    private suspend fun finishPersistedReset(epoch: Long) {
        val persistedState = resetTransitionMutex.withLock {
            stateStore.finishReset(epoch)
        }
        updateCachedState(persistedState)
    }

    private suspend fun readState(): SessionLifecycleState {
        val inMemoryResetState = stateMutex.withLock {
            if (resetStartedInMemory) {
                cachedState ?: SessionLifecycleState(epoch = 0L, resetInProgress = true)
            } else {
                null
            }
        }
        if (inMemoryResetState != null) {
            return inMemoryResetState
        }
        return readPersistedStateRespectingResetIntent()
    }

    private suspend fun readPersistedStateRespectingResetIntent(): SessionLifecycleState {
        val persistedState = stateStore.snapshot()
        return stateMutex.withLock {
            if (resetStartedInMemory && !persistedState.resetInProgress) {
                cachedState ?: SessionLifecycleState(epoch = 0L, resetInProgress = true)
            } else {
                updateCachedStateLocked(persistedState)
            }
        }
    }

    private suspend fun refreshCachedState(clearLocalResetStart: Boolean = false): SessionLifecycleState {
        val persistedState = stateStore.snapshot()
        return updateCachedState(persistedState, clearLocalResetStart)
    }

    private suspend fun refreshCachedStateAfterResetFailure(owner: ResetOwner): SessionLifecycleState {
        val releasedOwner = releaseActiveResetOwnerAfterFailure(owner)
        val persistedState = stateStore.snapshot()
        return stateMutex.withLock {
            if (activeResetOwner == null) {
                updateCachedStateLocked(persistedState, clearLocalResetStart = releasedOwner)
            } else {
                updateCachedStateRespectingActiveResetLocked(persistedState)
            }
        }
    }

    private suspend fun releaseActiveResetOwnerAfterFailure(owner: ResetOwner): Boolean =
        stateMutex.withLock {
            val ownerStillActive = activeResetOwner?.id == owner.id
            if (ownerStillActive) {
                activeResetOwner = null
                resetStartedLocally = false
            }
            ownerStillActive
        }

    private suspend fun updateCachedState(
        state: SessionLifecycleState,
        clearLocalResetStart: Boolean = false
    ): SessionLifecycleState =
        stateMutex.withLock {
            updateCachedStateLocked(state, clearLocalResetStart)
        }

    private fun updateCachedStateLocked(
        state: SessionLifecycleState,
        clearLocalResetStart: Boolean = false
    ): SessionLifecycleState {
        if (activeResetOwner != null && !state.resetInProgress && !clearLocalResetStart) {
            return cachedState ?: state.copy(resetInProgress = true)
        }
        cachedState = state
        resetStartedInMemory = state.resetInProgress
        if (!state.resetInProgress || clearLocalResetStart) {
            resetStartedLocally = false
        }
        return state
    }

    private fun updateCachedStateRespectingActiveResetLocked(
        state: SessionLifecycleState
    ): SessionLifecycleState {
        if (activeResetOwner == null) {
            return updateCachedStateLocked(state)
        }
        val resetState = state.copy(resetInProgress = true)
        cachedState = resetState
        resetStartedInMemory = true
        resetStartedLocally = true
        return resetState
    }

    private suspend fun currentCachedStateOr(fallback: SessionLifecycleState): SessionLifecycleState =
        stateMutex.withLock {
            if (resetStartedInMemory) {
                cachedState ?: SessionLifecycleState(epoch = 0L, resetInProgress = true)
            } else {
                cachedState ?: fallback
            }
        }

    private fun markResetStartedInMemoryLocked(): Long {
        val ownerId = nextResetOwnerId + 1
        nextResetOwnerId = ownerId
        activeResetOwner = ResetOwner(ownerId, cachedState?.epoch)
        resetStartedInMemory = true
        resetStartedLocally = true
        cachedState = (cachedState ?: SessionLifecycleState(epoch = 0L, resetInProgress = false))
            .copy(resetInProgress = true)
        return ownerId
    }

    private fun isResetInProgress(): Boolean =
        resetStartedInMemory || cachedState?.resetInProgress == true

    private suspend fun activeResetOwnerMatches(owner: ResetOwner): Boolean =
        stateMutex.withLock {
            activeResetOwner?.id == owner.id && activeResetOwner?.epoch == owner.epoch
        }

    private suspend fun clearFailedResetStart(ownerId: Long) {
        val releasedOwner = releaseFailedResetStartOwner(ownerId)
        val persistedState = resetTransitionMutex.withLock {
            stateStore.snapshot()
        }
        stateMutex.withLock {
            if (activeResetOwner == null) {
                updateCachedStateLocked(persistedState, clearLocalResetStart = releasedOwner)
            } else {
                updateCachedStateRespectingActiveResetLocked(persistedState)
            }
        }
    }

    private suspend fun releaseFailedResetStartOwner(ownerId: Long): Boolean =
        stateMutex.withLock {
            val ownerStillActive = activeResetOwner?.id == ownerId
            if (ownerStillActive) {
                activeResetOwner = null
                resetStartedLocally = false
            }
            ownerStillActive
        }

    private fun requireSyncEpoch(epoch: Long, state: SessionLifecycleState) {
        if (state.resetInProgress || state.epoch != epoch) {
            throw SyncOperationInvalidatedException("Session epoch changed from $epoch to ${state.epoch}")
        }
    }

    private data class ResetOwner(
        val id: Long,
        val epoch: Long?
    )
}

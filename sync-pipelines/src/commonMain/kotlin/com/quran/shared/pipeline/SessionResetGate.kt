package com.quran.shared.pipeline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a session reset apart from the work it must not overlap: local writes and sign-ins.
 *
 * Any number of [withShared] blocks run at once. A [withExclusive] block (a reset) waits for the
 * shared blocks already running to end, then runs alone. While it waits or runs, new shared blocks
 * queue behind it, so a stream of writes cannot hold a reset off. Exclusive blocks run one at a time.
 *
 * The gate only orders blocks; [SessionLifecycleCoordinator] checks the reset state around it.
 */
internal class SessionResetGate {
    /** Held by an exclusive block from the moment it starts waiting; a shared block takes it briefly to enter. */
    private val exclusive = Mutex()
    private val sharedHolders = MutableStateFlow(0)

    suspend fun <T> withShared(block: suspend () -> T): T {
        exclusive.withLock {
            sharedHolders.update { it + 1 }
        }
        try {
            return block()
        } finally {
            sharedHolders.update { it - 1 }
        }
    }

    suspend fun <T> withExclusive(block: suspend () -> T): T =
        exclusive.withLock {
            sharedHolders.first { it == 0 }
            block()
        }
}

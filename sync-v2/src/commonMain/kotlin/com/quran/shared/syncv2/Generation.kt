package com.quran.shared.syncv2

import com.quran.shared.syncv2.db.SyncV2Database

/** Local data was wiped (logout or account switch) while this sync was in flight. */
class SyncSupersededException : IllegalStateException("local data was wiped during this sync")

/**
 * The wipe epoch a sync started under. Every write of a sync goes through [transaction], which refuses to commit
 * once a wipe has bumped the epoch, so a sync in flight can't bring back data that a logout or account switch removed.
 */
class Generation(private val database: SyncV2Database, private val store: SyncStore, private val epoch: Long) {
    fun <T> transaction(block: () -> T): T = database.transactionWithResult {
        if (store.epoch != epoch) throw SyncSupersededException()
        block()
    }
}

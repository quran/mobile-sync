package com.quran.shared.syncengine

interface SyncResourceAdapter {
    val resourceName: String
    val localModificationDateFetcher: LocalModificationDateFetcher

    suspend fun buildPlan(
        lastModificationDate: Long,
        remoteMutations: List<SyncMutation>
    ): ResourceSyncPlan

    suspend fun didCompleteSync(newToken: Long) = Unit

    suspend fun didFail(message: String)
}

interface ResourceSyncPlan {
    val resourceName: String

    fun mutationsToPush(): List<SyncMutation>

    suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>)
}

internal interface PreDependencyDeletionSyncResourceAdapter : SyncResourceAdapter {
    suspend fun buildPreDependencyDeletionPlan(
        lastModificationDate: Long,
        remoteMutations: List<SyncMutation>
    ): ResourceSyncPlan?
}

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class DefaultCollectionBookmarkRemoteMappingTest {

    @Test
    fun `bookmark default membership is parsed without creating a collection bookmark mutation`() = runTest {
        val remotes = listOf(
            syncBookmark("remote-true", ayah = 2, isInDefaultCollection = true),
            syncBookmark("remote-false", ayah = 3, isInDefaultCollection = false),
            syncBookmark("remote-missing", ayah = 4, isInDefaultCollection = null)
        )

        val persisted = execute(remotes)

        assertEquals(listOf("remote-true", "remote-false", "remote-missing"), persisted.map { it.remoteID })
        assertEquals(
            listOf(true, false, null),
            persisted.map { (it.model as SyncBookmark.AyahBookmark).isInDefaultCollection }
        )
    }

    private suspend fun execute(
        remotes: List<SyncMutation>
    ): List<RemoteModelMutation<SyncBookmark>> {
        var capturedRemote: List<RemoteModelMutation<SyncBookmark>>? = null
        val adapter = BookmarksSyncAdapter(
            BookmarksSynchronizationConfigurations(
                localDataFetcher = object : LocalDataFetcher<SyncBookmark> {
                    override suspend fun fetchLocalMutations(
                        lastModified: Long
                    ): List<LocalModelMutation<SyncBookmark>> = emptyList()

                    override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                        remoteIDs.associateWith { false }

                    override suspend fun fetchLocalModel(remoteId: String): SyncBookmark? = null
                },
                resultNotifier = object : ResultNotifier<SyncBookmark> {
                    override suspend fun didSucceed(
                        newToken: Long,
                        newRemoteMutations: List<RemoteModelMutation<SyncBookmark>>,
                        processedLocalMutations: List<LocalModelMutation<SyncBookmark>>
                    ) {
                        capturedRemote = newRemoteMutations
                    }

                    override suspend fun didFail(message: String) {
                        fail("didFail called: $message")
                    }
                },
                localModificationDateFetcher = object : LocalModificationDateFetcher {
                    override suspend fun localLastModificationDate(): Long? = 0L
                }
            )
        )

        val plan = adapter.buildPlan(lastModificationDate = 0L, remoteMutations = remotes)
        plan.complete(newToken = 1L, pushedMutations = emptyList())
        return checkNotNull(capturedRemote)
    }

    private fun syncBookmark(
        id: String,
        ayah: Int,
        isInDefaultCollection: Boolean?
    ): SyncMutation =
        SyncMutation(
            resource = "BOOKMARK",
            resourceId = id,
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("bookmarkType", "ayah")
                put("key", 1)
                put("verseNumber", ayah)
                isInDefaultCollection?.let { put("isInDefaultCollection", it) }
                put("clientCreatedAt", "2026-07-06T21:55:26.000Z")
                put("clientUpdatedAt", "2026-07-06T21:55:26.000Z")
            },
            timestamp = 1L
        )
}

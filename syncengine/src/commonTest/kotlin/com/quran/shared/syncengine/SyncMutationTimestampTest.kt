@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.NoteAyah
import com.quran.shared.syncengine.model.NoteRange
import com.quran.shared.syncengine.model.SyncCollection
import com.quran.shared.syncengine.model.SyncNote
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Instant

class SyncMutationTimestampTest {

    @Test
    fun `collection mutations carry local model timestamp`() = runTest {
        val localMutation = LocalModelMutation(
            model = SyncCollection(
                id = "local-collection",
                name = "Favorites",
                lastModified = Instant.fromEpochMilliseconds(12_345)
            ),
            remoteID = null,
            localID = "local-collection",
            mutation = Mutation.CREATED
        )

        val adapter = CollectionsSyncAdapter(
            CollectionsSynchronizationConfigurations(
                localDataFetcher = collectionFetcher(listOf(localMutation)),
                resultNotifier = noopCollectionNotifier(),
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()

        assertEquals(12_345L, mutation.timestamp)
    }

    @Test
    fun `note mutations carry local model timestamp`() = runTest {
        val localMutation = LocalModelMutation(
            model = SyncNote(
                id = "local-note",
                body = "Note",
                ranges = listOf(
                    NoteRange(
                        start = NoteAyah(sura = 2, ayah = 255),
                        end = NoteAyah(sura = 2, ayah = 255)
                    )
                ),
                lastModified = Instant.fromEpochMilliseconds(54_321)
            ),
            remoteID = null,
            localID = "local-note",
            mutation = Mutation.CREATED
        )

        val adapter = NotesSyncAdapter(
            NotesSynchronizationConfigurations(
                localDataFetcher = noteFetcher(listOf(localMutation)),
                resultNotifier = noopNoteNotifier(),
                localModificationDateFetcher = zeroModificationDateFetcher()
            )
        )

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()

        assertEquals(54_321L, mutation.timestamp)
    }

    private fun collectionFetcher(
        localMutations: List<LocalModelMutation<SyncCollection>>
    ): LocalDataFetcher<SyncCollection> {
        return object : LocalDataFetcher<SyncCollection> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncCollection>> {
                return localMutations
            }

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
                return remoteIDs.associateWith { true }
            }

            override suspend fun fetchLocalModel(remoteId: String): SyncCollection? = null
        }
    }

    private fun noteFetcher(
        localMutations: List<LocalModelMutation<SyncNote>>
    ): LocalDataFetcher<SyncNote> {
        return object : LocalDataFetcher<SyncNote> {
            override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncNote>> {
                return localMutations
            }

            override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> {
                return remoteIDs.associateWith { true }
            }

            override suspend fun fetchLocalModel(remoteId: String): SyncNote? = null
        }
    }

    private fun noopCollectionNotifier(): ResultNotifier<SyncCollection> {
        return object : ResultNotifier<SyncCollection> {
            override suspend fun didSucceed(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<SyncCollection>>,
                processedLocalMutations: List<LocalModelMutation<SyncCollection>>
            ) = Unit

            override suspend fun didFail(message: String) {
                fail("didFail called: $message")
            }
        }
    }

    private fun noopNoteNotifier(): ResultNotifier<SyncNote> {
        return object : ResultNotifier<SyncNote> {
            override suspend fun didSucceed(
                newToken: Long,
                newRemoteMutations: List<RemoteModelMutation<SyncNote>>,
                processedLocalMutations: List<LocalModelMutation<SyncNote>>
            ) = Unit

            override suspend fun didFail(message: String) {
                fail("didFail called: $message")
            }
        }
    }

    private fun zeroModificationDateFetcher(): LocalModificationDateFetcher {
        return object : LocalModificationDateFetcher {
            override suspend fun localLastModificationDate(): Long? = 0L
        }
    }
}

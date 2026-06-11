@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.SyncCollection
import com.quran.shared.syncengine.model.SyncNote
import com.quran.shared.syncengine.model.SyncReadingSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SimpleResourceRemoteIdValidationTest {
    @Test
    fun `bookmark replay with blank resource id fails before completion`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            BookmarksSyncAdapter(
                BookmarksSynchronizationConfigurations(
                    localDataFetcher = emptyLocalDataFetcher<SyncBookmark>(),
                    resultNotifier = noOpResultNotifier(),
                    localModificationDateFetcher = zeroModificationDateFetcher()
                )
            ).buildPlan(0L, listOf(blankReplayMutation("BOOKMARK")))
        }
    }

    @Test
    fun `collection replay with blank resource id fails before completion`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            CollectionsSyncAdapter(
                CollectionsSynchronizationConfigurations(
                    localDataFetcher = emptyLocalDataFetcher<SyncCollection>(),
                    resultNotifier = noOpResultNotifier(),
                    localModificationDateFetcher = zeroModificationDateFetcher()
                )
            ).buildPlan(0L, listOf(blankReplayMutation("COLLECTION")))
        }
    }

    @Test
    fun `note replay with blank resource id fails before completion`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            NotesSyncAdapter(
                NotesSynchronizationConfigurations(
                    localDataFetcher = emptyLocalDataFetcher<SyncNote>(),
                    resultNotifier = noOpResultNotifier(),
                    localModificationDateFetcher = zeroModificationDateFetcher()
                )
            ).buildPlan(0L, listOf(blankReplayMutation("NOTE")))
        }
    }

    @Test
    fun `reading session replay with blank resource id fails before completion`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            ReadingSessionsSyncAdapter(
                ReadingSessionsSynchronizationConfigurations(
                    localDataFetcher = emptyLocalDataFetcher<SyncReadingSession>(),
                    resultNotifier = noOpResultNotifier(),
                    localModificationDateFetcher = zeroModificationDateFetcher()
                )
            ).buildPlan(0L, listOf(blankReplayMutation("READING_SESSION")))
        }
    }

    @Test
    fun `simple parser validates resource id before dropping unparseable mutation`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            listOf(
                SyncMutation(
                    resource = "NOTE",
                    resourceId = null,
                    mutation = Mutation.DELETED,
                    data = null,
                    timestamp = 1000L
                )
            ).mapSimpleRemoteModelMutations<SyncNote>("NOTE") { null }
        }

        assertEquals("Missing resourceId for remote NOTE mutation", exception.message)
    }
}

private fun blankReplayMutation(resource: String): SyncMutation =
    SyncMutation(
        resource = resource,
        resourceId = "",
        mutation = Mutation.DELETED,
        data = null,
        timestamp = 1000L
    )

private fun <T> emptyLocalDataFetcher(): LocalDataFetcher<T> =
    object : LocalDataFetcher<T> {
        override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<T>> =
            emptyList()

        override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
            remoteIDs.associateWith { false }

        override suspend fun fetchLocalModel(remoteId: String): T? = null
    }

private fun <T> noOpResultNotifier(): ResultNotifier<T> =
    object : ResultNotifier<T> {
        override suspend fun didSucceed(
            newToken: Long,
            newRemoteMutations: List<RemoteModelMutation<T>>,
            processedLocalMutations: List<LocalModelMutation<T>>
        ) = Unit

        override suspend fun didFail(message: String) = Unit
    }

private fun zeroModificationDateFetcher(): LocalModificationDateFetcher =
    object : LocalModificationDateFetcher {
        override suspend fun localLastModificationDate(): Long? = 0L
    }

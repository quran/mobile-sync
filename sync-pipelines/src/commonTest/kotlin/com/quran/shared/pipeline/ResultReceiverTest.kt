@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.pipeline

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.util.fromPlatform
import com.quran.shared.persistence.repository.PersistenceWriteBoundaryGuard
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.syncengine.SyncOperationInvalidatedException
import com.quran.shared.syncengine.model.SyncBookmark
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.coroutines.toSuspendSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ResultReceiverTest {

    @Test
    fun `bookmark receiver applies resource changes without completing sync token`() = runTest {
        val events = mutableListOf<String>()
        val receiver = ResultReceiver(
            bookmarksRepository = RecordingBookmarksRepository(events),
            callback = object : SyncEngineCallback {
                override suspend fun synchronizationDone(newLastModificationDate: Long) {
                    events += "done-$newLastModificationDate"
                }

                override suspend fun encounteredError(errorMsg: String) {
                    events += "error-$errorMsg"
                }
            }
        )

        receiver.didSucceed(
            newToken = 11L,
            newRemoteMutations = emptyList<RemoteModelMutation<SyncBookmark>>(),
            processedLocalMutations = emptyList<LocalModelMutation<SyncBookmark>>()
        )

        assertEquals(
            listOf("bookmarks-applied"),
            events
        )
    }

    @Test
    fun `page reading bookmark mutations are routed to unified bookmarks repository`() = runTest {
        val readingUpdates = mutableListOf<RemoteModelMutation<RemoteBookmark>>()
        val receiver = ResultReceiver(
            bookmarksRepository = RecordingBookmarksRepository(
                events = mutableListOf(),
                remoteUpdates = readingUpdates
            ),
            callback = object : SyncEngineCallback {
                override suspend fun synchronizationDone(newLastModificationDate: Long) = Unit
                override suspend fun encounteredError(errorMsg: String) = Unit
            }
        )

        val createdAt = Instant.fromEpochMilliseconds(500)
        receiver.didSucceed(
            newToken = 11L,
            newRemoteMutations = listOf(
                RemoteModelMutation(
                    model = SyncBookmark.PageBookmark(
                        id = "remote-page-reading",
                        page = 42,
                        isReading = true,
                        lastModified = Instant.fromEpochMilliseconds(1000),
                        createdAt = createdAt
                    ),
                    remoteID = "remote-page-reading",
                    mutation = com.quran.shared.mutations.Mutation.CREATED
                )
            ),
            processedLocalMutations = emptyList()
        )

        val model = readingUpdates.single().model as RemoteBookmark.Page
        assertEquals(42, model.page)
        assertEquals(true, model.isReading)
        assertEquals(createdAt, model.createdAt?.fromPlatform())
    }

    @Test
    fun `settings sync callback persists final sync token`() = runTest {
        val store = SyncSettingsLocalModificationDateStore(MapSettings().toSuspendSettings())
        val callback = SettingsSyncEngineCallback(store)

        callback.synchronizationDone(44L)

        assertEquals(44L, store.localLastModificationDate())
    }

    @Test
    fun `resource apply failure leaves settings sync token unchanged`() = runTest {
        val store = SyncSettingsLocalModificationDateStore(MapSettings().toSuspendSettings())
        store.updateLastModificationDate(7L)
        val receiver = ResultReceiver(
            bookmarksRepository = FailingBookmarksRepository(),
            callback = SettingsSyncEngineCallback(store)
        )

        assertFailsWith<IllegalStateException> {
            receiver.didSucceed(
                newToken = 11L,
                newRemoteMutations = emptyList(),
                processedLocalMutations = emptyList()
            )
        }

        assertEquals(7L, store.localLastModificationDate())
    }

    @Test
    fun `stale write boundary skips bookmark repository apply`() = runTest {
        val events = mutableListOf<String>()
        val receiver = ResultReceiver(
            bookmarksRepository = RecordingBookmarksRepository(events),
            callback = SettingsSyncEngineCallback(SyncSettingsLocalModificationDateStore(MapSettings().toSuspendSettings())),
            writeBoundaryGuard = SyncWriteBoundaryGuard {
                throw SyncOperationInvalidatedException("stale epoch")
            }
        )

        assertFailsWith<SyncOperationInvalidatedException> {
            receiver.didSucceed(
                newToken = 11L,
                newRemoteMutations = emptyList<RemoteModelMutation<SyncBookmark>>(),
                processedLocalMutations = emptyList<LocalModelMutation<SyncBookmark>>()
            )
        }

        assertEquals(emptyList(), events)
    }

    @Test
    fun `stale write boundary skips settings sync token persistence`() = runTest {
        val store = SyncSettingsLocalModificationDateStore(MapSettings().toSuspendSettings())
        store.updateLastModificationDate(7L)
        val callback = SettingsSyncEngineCallback(
            syncLocalModificationDateStore = store,
            writeBoundaryGuard = SyncWriteBoundaryGuard {
                throw SyncOperationInvalidatedException("stale epoch")
            }
        )

        assertFailsWith<SyncOperationInvalidatedException> {
            callback.synchronizationDone(44L)
        }

        assertEquals(7L, store.localLastModificationDate())
    }
}

private class RecordingBookmarksRepository(
    private val events: MutableList<String>,
    private val remoteUpdates: MutableList<RemoteModelMutation<RemoteBookmark>> = mutableListOf()
) : BookmarksSynchronizationRepository {
    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<RemoteBookmark>> = emptyList()

    override suspend fun markMutatedBookmarksInFlight(
        acks: List<com.quran.shared.mutations.LocalMutationAck>
    ): List<com.quran.shared.mutations.LocalMutationAck> = emptyList()

    override suspend fun rollbackMutatedBookmarksInFlight(
        acks: List<com.quran.shared.mutations.LocalMutationAck>
    ) = Unit

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark>>,
        localMutationsToClear: List<LocalModelMutation<RemoteBookmark>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) {
        writeBoundaryGuard.checkWriteBoundary()
        remoteUpdates += updatesToPersist
        events += "bookmarks-applied"
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        remoteIDs.associateWith { false }

    override suspend fun fetchBookmarkByRemoteId(remoteId: String): RemoteBookmark? = null
}

private class FailingBookmarksRepository : BookmarksSynchronizationRepository {
    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<RemoteBookmark>> = emptyList()

    override suspend fun markMutatedBookmarksInFlight(
        acks: List<com.quran.shared.mutations.LocalMutationAck>
    ): List<com.quran.shared.mutations.LocalMutationAck> = emptyList()

    override suspend fun rollbackMutatedBookmarksInFlight(
        acks: List<com.quran.shared.mutations.LocalMutationAck>
    ) = Unit

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark>>,
        localMutationsToClear: List<LocalModelMutation<RemoteBookmark>>,
        writeBoundaryGuard: PersistenceWriteBoundaryGuard
    ) {
        writeBoundaryGuard.checkWriteBoundary()
        throw IllegalStateException("apply failed")
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        remoteIDs.associateWith { false }

    override suspend fun fetchBookmarkByRemoteId(remoteId: String): RemoteBookmark? = null
}

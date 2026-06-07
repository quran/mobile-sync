@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.pipeline

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.syncengine.model.SyncBookmark
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ResultReceiverTest {

    @Test
    fun `sync completion callback is deferred until resource changes are applied`() = runTest {
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

        receiver.didCompleteSync(10L)

        assertEquals(
            listOf("bookmarks-applied", "done-10"),
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

        receiver.didSucceed(
            newToken = 11L,
            newRemoteMutations = listOf(
                RemoteModelMutation(
                    model = SyncBookmark.PageBookmark(
                        id = "remote-page-reading",
                        page = 42,
                        isReading = true,
                        lastModified = Instant.fromEpochMilliseconds(1000)
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
    }
}

private class RecordingBookmarksRepository(
    private val events: MutableList<String>,
    private val remoteUpdates: MutableList<RemoteModelMutation<RemoteBookmark>> = mutableListOf()
) : BookmarksSynchronizationRepository {
    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<RemoteBookmark>> = emptyList()

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark>>,
        localMutationsToClear: List<LocalModelMutation<RemoteBookmark>>
    ) {
        remoteUpdates += updatesToPersist
        events += "bookmarks-applied"
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        remoteIDs.associateWith { false }

    override suspend fun fetchBookmarkByRemoteId(remoteId: String): RemoteBookmark? = null
}

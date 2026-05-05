@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.pipeline

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.persistence.input.RemoteBookmark
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksSynchronizationRepository
import com.quran.shared.persistence.repository.readingbookmark.repository.ReadingBookmarksSynchronizationRepository
import com.quran.shared.syncengine.model.SyncBookmark
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultReceiverTest {

    @Test
    fun `sync completion callback is deferred until resource changes are applied`() = runTest {
        val events = mutableListOf<String>()
        val receiver = ResultReceiver(
            bookmarksRepository = RecordingBookmarksRepository(events),
            readingBookmarksRepository = RecordingReadingBookmarksRepository(events),
            callback = object : SyncEngineCallback {
                override fun synchronizationDone(newLastModificationDate: Long) {
                    events += "done-$newLastModificationDate"
                }

                override fun encounteredError(errorMsg: String) {
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
            listOf("bookmarks-applied", "reading-bookmarks-applied"),
            events
        )

        receiver.didCompleteSync(10L)

        assertEquals(
            listOf("bookmarks-applied", "reading-bookmarks-applied", "done-10"),
            events
        )
    }
}

private class RecordingBookmarksRepository(
    private val events: MutableList<String>
) : BookmarksSynchronizationRepository {
    override suspend fun fetchMutatedBookmarks(): List<LocalModelMutation<AyahBookmark>> = emptyList()

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark.Ayah>>,
        localMutationsToClear: List<LocalModelMutation<AyahBookmark>>
    ) {
        events += "bookmarks-applied"
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        remoteIDs.associateWith { false }

    override suspend fun fetchBookmarkByRemoteId(remoteId: String): AyahBookmark? = null
}

private class RecordingReadingBookmarksRepository(
    private val events: MutableList<String>
) : ReadingBookmarksSynchronizationRepository {
    override suspend fun fetchMutatedReadingBookmarks(): List<LocalModelMutation<ReadingBookmark>> = emptyList()

    override suspend fun applyRemoteChanges(
        updatesToPersist: List<RemoteModelMutation<RemoteBookmark.Ayah>>,
        localMutationsToClear: List<LocalModelMutation<ReadingBookmark>>
    ) {
        events += "reading-bookmarks-applied"
    }

    override suspend fun remoteResourcesExist(remoteIDs: List<String>): Map<String, Boolean> =
        remoteIDs.associateWith { false }

    override suspend fun fetchReadingBookmarkByRemoteId(remoteId: String): ReadingBookmark? = null
}

@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CollectionBookmarksConflictResolverTest {

    @Test
    fun `resolve pushes custom link delete over replayed remote create echo`() {
        val ack = LocalMutationAck(
            localID = "local-link-1",
            resource = LocalMutationResource.COLLECTION_BOOKMARK,
            facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
            observedPendingOp = Mutation.DELETED,
            observedPendingVersion = 3L
        )
        val localDelete = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 9,
                ayah = 1,
                bookmarkId = "remote-bookmark-1",
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = "remote-collection-1-remote-bookmark-1",
            localID = "local-link-1",
            mutation = Mutation.DELETED,
            ack = ack
        )
        val remoteCreateEcho = RemoteModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-1",
                sura = 9,
                ayah = 1,
                bookmarkId = "remote-bookmark-1",
                lastModified = Instant.fromEpochMilliseconds(2000)
            ),
            remoteID = "remote-collection-1-remote-bookmark-1",
            mutation = Mutation.CREATED
        )

        val result = CollectionBookmarksConflictResolver(
            conflicts = listOf(
                ResourceConflict(
                    localMutations = listOf(localDelete),
                    remoteMutations = listOf(remoteCreateEcho)
                )
            )
        ).resolve()

        assertEquals(emptyList<RemoteModelMutation<SyncCollectionBookmark>>(), result.mutationsToPersist)
        val pushed = result.mutationsToPush.single()
        assertEquals(localDelete, pushed)
        assertEquals(ack, pushed.ack)
    }
}

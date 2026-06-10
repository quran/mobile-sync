@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.LOCAL_MUTATION_ENTITY_FACET
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CollectionBookmarksRemoteMutationsPreprocessorTest {

    @Test
    fun `modified to created transform preserves ACK metadata`() = runTest {
        val ack = LocalMutationAck(
            localID = "local-link-id",
            resource = LocalMutationResource.COLLECTION_BOOKMARK,
            facet = LOCAL_MUTATION_ENTITY_FACET,
            observedPendingOp = Mutation.MODIFIED,
            observedPendingVersion = 7L
        )
        val mutation: RemoteModelMutation<SyncCollectionBookmark> = RemoteModelMutation(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection-id",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1000L),
                bookmarkId = "remote-bookmark-id"
            ),
            remoteID = "remote-link-id",
            mutation = Mutation.MODIFIED,
            ack = ack
        )
        val preprocessor = CollectionBookmarksRemoteMutationsPreprocessor {
            error("No local existence check is needed without remote deletes.")
        }

        val result = preprocessor.preprocess(listOf(mutation)).single()

        assertEquals(Mutation.CREATED, result.mutation)
        assertEquals(ack, result.ack)
    }
}

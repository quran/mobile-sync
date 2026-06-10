@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine.conflict

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncCollection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class CollectionsConflictResolverTest {

    @Test
    fun `resolve pushes local delete over replayed remote create echo`() {
        val remoteMutation = RemoteModelMutation(
            model = SyncCollection(
                id = "remote-1",
                name = "Favorites",
                lastModified = Instant.fromEpochMilliseconds(2000)
            ),
            remoteID = "remote-1",
            mutation = Mutation.CREATED
        )
        val localMutation = LocalModelMutation(
            model = SyncCollection(
                id = "local-1",
                name = "Favorites",
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = "remote-1",
            localID = "local-1",
            mutation = Mutation.DELETED
        )

        val result = CollectionsConflictResolver(
            conflicts = listOf(
                ResourceConflict(
                    localMutations = listOf(localMutation),
                    remoteMutations = listOf(remoteMutation)
                )
            )
        ).resolve()

        assertEquals(emptyList(), result.mutationsToPersist)
        assertEquals(listOf(localMutation), result.mutationsToPush)
    }

    @Test
    fun `resolve preserves sibling pending local mutation when tombstone delete wins over create echo`() {
        val remoteCreateEcho = RemoteModelMutation(
            model = SyncCollection(
                id = "remote-tombstone",
                name = "Favorites",
                lastModified = Instant.fromEpochMilliseconds(3000)
            ),
            remoteID = "remote-tombstone",
            mutation = Mutation.CREATED
        )
        val tombstoneDelete = LocalModelMutation(
            model = SyncCollection(
                id = "local-tombstone",
                name = "Favorites",
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = "remote-tombstone",
            localID = "local-tombstone",
            mutation = Mutation.DELETED
        )
        val siblingEdit = LocalModelMutation(
            model = SyncCollection(
                id = "local-active",
                name = "Favorites",
                lastModified = Instant.fromEpochMilliseconds(2000)
            ),
            remoteID = "remote-active",
            localID = "local-active",
            mutation = Mutation.MODIFIED
        )

        val result = CollectionsConflictResolver(
            conflicts = listOf(
                ResourceConflict(
                    localMutations = listOf(tombstoneDelete, siblingEdit),
                    remoteMutations = listOf(remoteCreateEcho)
                )
            )
        ).resolve()

        assertEquals(emptyList(), result.mutationsToPersist)
        assertEquals(listOf(tombstoneDelete, siblingEdit), result.mutationsToPush)
    }
}

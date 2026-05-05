package com.quran.shared.syncengine

import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.network.MutationsResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SynchronizationClientPhasingTest {

    @Test
    fun `collection bookmark plan is built after primary resources complete`() = runTest {
        val events = mutableListOf<String>()
        var completedPrimaryResources = 0
        val pushedTokens = mutableListOf<Long>()

        val bookmarkAdapter = RecordingAdapter(
            resourceName = "BOOKMARK",
            events = events,
            onComplete = {
                completedPrimaryResources += 1
            },
            onSyncComplete = { token -> events += "sync-complete-BOOKMARK-$token" }
        )
        val collectionAdapter = RecordingAdapter(
            resourceName = "COLLECTION",
            events = events,
            onComplete = {
                completedPrimaryResources += 1
            },
            onSyncComplete = { token -> events += "sync-complete-COLLECTION-$token" }
        )
        val collectionBookmarkAdapter = RecordingAdapter(
            resourceName = "COLLECTION_BOOKMARK",
            events = events,
            onBuild = {
                assertEquals(
                    2,
                    completedPrimaryResources,
                    "Collection bookmark planning should see remote IDs persisted by primary resources."
                )
            },
            onSyncComplete = { token -> events += "sync-complete-COLLECTION_BOOKMARK-$token" }
        )
        val notesAdapter = RecordingAdapter(
            resourceName = "NOTE",
            events = events,
            onSyncComplete = { token -> events += "sync-complete-NOTE-$token" }
        )

        executeDependencyAwareSync(
            resourceAdapters = listOf(
                bookmarkAdapter,
                collectionAdapter,
                collectionBookmarkAdapter,
                notesAdapter
            ),
            initialLastModificationDate = 1L,
            remoteResponse = MutationsResponse(
                lastModificationDate = 10L,
                mutations = emptyList()
            ),
            pushMutations = { mutations, mutationToken ->
                pushedTokens += mutationToken
                MutationsResponse(
                    lastModificationDate = mutationToken + 1,
                    mutations = mutations.mapIndexed { index, mutation ->
                        mutation.copy(resourceId = "${mutation.resource.lowercase()}-$index")
                    }
                )
            }
        )

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "build-COLLECTION",
                "complete-BOOKMARK-1",
                "complete-COLLECTION-1",
                "build-COLLECTION_BOOKMARK",
                "complete-COLLECTION_BOOKMARK-1",
                "build-NOTE",
                "complete-NOTE-1",
                "sync-complete-BOOKMARK-10",
                "sync-complete-COLLECTION-10",
                "sync-complete-COLLECTION_BOOKMARK-10",
                "sync-complete-NOTE-10"
            ),
            events
        )
        assertEquals(listOf(10L, 11L, 12L), pushedTokens)
    }

    @Test
    fun `shared token is not completed when later phase fails`() = runTest {
        val events = mutableListOf<String>()

        val bookmarkAdapter = RecordingAdapter(
            resourceName = "BOOKMARK",
            events = events,
            onSyncComplete = { token -> events += "sync-complete-BOOKMARK-$token" }
        )
        val collectionBookmarkAdapter = RecordingAdapter(
            resourceName = "COLLECTION_BOOKMARK",
            events = events,
            onBuild = {
                throw IllegalStateException("later phase failed")
            }
        )

        assertFailsWith<IllegalStateException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    bookmarkAdapter,
                    collectionBookmarkAdapter
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken ->
                    MutationsResponse(
                        lastModificationDate = mutationToken + 1,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.copy(resourceId = "${mutation.resource.lowercase()}-$index")
                        }
                    )
                }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "complete-BOOKMARK-1",
                "build-COLLECTION_BOOKMARK"
            ),
            events
        )
    }

    @Test
    fun `dependency aware phases preserve adapter order inside each phase`() {
        val adapters = listOf(
            RecordingAdapter("READING_SESSION", mutableListOf()),
            RecordingAdapter("COLLECTION_BOOKMARK", mutableListOf()),
            RecordingAdapter("BOOKMARK", mutableListOf()),
            RecordingAdapter("NOTE", mutableListOf()),
            RecordingAdapter("COLLECTION", mutableListOf())
        )

        val phases = adapters.dependencyAwareSyncPhases()
            .map { phase -> phase.map { it.resourceName } }

        assertEquals(
            listOf(
                listOf("BOOKMARK", "COLLECTION"),
                listOf("COLLECTION_BOOKMARK"),
                listOf("READING_SESSION", "NOTE")
            ),
            phases
        )
    }
}

private class RecordingAdapter(
    override val resourceName: String,
    private val events: MutableList<String>,
    private val onBuild: () -> Unit = {},
    private val onComplete: () -> Unit = {},
    private val onSyncComplete: (Long) -> Unit = {}
) : SyncResourceAdapter {
    override val localModificationDateFetcher: LocalModificationDateFetcher =
        object : LocalModificationDateFetcher {
            override suspend fun localLastModificationDate(): Long = 0L
        }

    override suspend fun buildPlan(
        lastModificationDate: Long,
        remoteMutations: List<SyncMutation>
    ): ResourceSyncPlan {
        events += "build-$resourceName"
        onBuild()
        return RecordingPlan(resourceName, events, onComplete)
    }

    override suspend fun didFail(message: String) = Unit

    override suspend fun didCompleteSync(newToken: Long) {
        onSyncComplete(newToken)
    }
}

private class RecordingPlan(
    override val resourceName: String,
    private val events: MutableList<String>,
    private val onComplete: () -> Unit
) : ResourceSyncPlan {
    override fun mutationsToPush(): List<SyncMutation> =
        listOf(
            SyncMutation(
                resource = resourceName,
                resourceId = null,
                mutation = Mutation.CREATED,
                data = null,
                timestamp = null
            )
        )

    override suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>) {
        events += "complete-$resourceName-$newToken"
        onComplete()
    }
}

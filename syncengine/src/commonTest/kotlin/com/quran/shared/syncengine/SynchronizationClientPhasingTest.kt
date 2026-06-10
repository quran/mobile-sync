package com.quran.shared.syncengine

import com.quran.shared.mutations.LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET
import com.quran.shared.mutations.LOCAL_MUTATION_BOOKMARK_READING_FACET
import com.quran.shared.mutations.LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET
import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.LocalMutationAck
import com.quran.shared.mutations.LocalMutationResource
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark
import com.quran.shared.syncengine.model.SyncCollectionBookmark
import com.quran.shared.syncengine.model.collectionBookmarkRemoteId
import com.quran.shared.syncengine.network.MutationsResponse
import com.quran.shared.syncengine.network.SyncNetworkException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.time.Instant

class SynchronizationClientPhasingTest {

    @Test
    fun `collection bookmark deletions are pushed before primary resources`() = runTest {
        val events = mutableListOf<String>()
        val pushedMutations = mutableListOf<List<SyncMutation>>()
        val pushedTokens = mutableListOf<Long>()
        var preDependencyDeleteCompleted = false

        val bookmarkAdapter = RecordingAdapter(
            resourceName = "BOOKMARK",
            events = events,
            onBuild = {
                assertEquals(
                    true,
                    preDependencyDeleteCompleted,
                    "Bookmark planning should run after collection bookmark deletions complete."
                )
            }
        )
        val collectionBookmarkAdapter = RecordingAdapter(
            resourceName = "COLLECTION_BOOKMARK",
            events = events,
            preDependencyDeletionMutation = SyncMutation(
                resource = "COLLECTION_BOOKMARK",
                resourceId = "remote-collection-bookmark-1",
                mutation = Mutation.DELETED,
                data = null,
                timestamp = null
            ),
            onPreDependencyDeletionComplete = {
                preDependencyDeleteCompleted = true
            }
        )

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
            pushMutations = { mutations, mutationToken, _ ->
                pushedTokens += mutationToken
                pushedMutations += mutations
                MutationsResponse(
                    lastModificationDate = mutationToken + 1,
                    mutations = mutations.mapIndexed { index, mutation ->
                        mutation.recordingAck(index)
                    }
                )
            },
            completeSync = { token -> events += "sync-complete-$token" }
        )

        assertEquals(
            listOf(
                "build-pre-COLLECTION_BOOKMARK",
                "complete-pre-COLLECTION_BOOKMARK-1",
                "build-BOOKMARK",
                "complete-BOOKMARK-1",
                "build-COLLECTION_BOOKMARK",
                "complete-COLLECTION_BOOKMARK-1",
                "sync-complete-10"
            ),
            events
        )
        assertEquals(listOf(10L, 11L, 12L), pushedTokens)
        assertEquals(listOf("COLLECTION_BOOKMARK"), pushedMutations[0].map { it.resource })
        assertEquals(listOf(Mutation.DELETED), pushedMutations[0].map { it.mutation })
        assertEquals(listOf("BOOKMARK"), pushedMutations[1].map { it.resource })
        assertEquals(listOf(Mutation.CREATED), pushedMutations[1].map { it.mutation })
        assertEquals(listOf("COLLECTION_BOOKMARK"), pushedMutations[2].map { it.resource })
        assertEquals(listOf(Mutation.CREATED), pushedMutations[2].map { it.mutation })
    }

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
            }
        )
        val collectionAdapter = RecordingAdapter(
            resourceName = "COLLECTION",
            events = events,
            onComplete = {
                completedPrimaryResources += 1
            }
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
            }
        )
        val notesAdapter = RecordingAdapter(
            resourceName = "NOTE",
            events = events
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
            pushMutations = { mutations, mutationToken, _ ->
                pushedTokens += mutationToken
                MutationsResponse(
                    lastModificationDate = mutationToken + 1,
                    mutations = mutations.mapIndexed { index, mutation ->
                        mutation.recordingAck(index)
                    }
                )
            },
            completeSync = { token -> events += "sync-complete-$token" }
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
                "sync-complete-10"
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
            events = events
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
                pushMutations = { mutations, mutationToken, _ ->
                    MutationsResponse(
                        lastModificationDate = mutationToken + 1,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.recordingAck(index)
                        }
                    )
                },
                completeSync = { token -> events += "sync-complete-$token" }
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
    fun `shared token is not completed when resource completion fails after accepted push`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true,
                        onComplete = {
                            throw IllegalStateException("local apply failed")
                        }
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken, _ ->
                    events += "push-${mutations.size}-$mutationToken"
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.recordingAck(index)
                        }
                    )
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "mark-in-flight-BOOKMARK",
                "mutations-BOOKMARK",
                "push-1-10",
                "complete-BOOKMARK-1"
            ),
            events
        )
    }

    @Test
    fun `unexpected pushed response mutation preserves in-flight markers for replay`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken, _ ->
                    events += "push-${mutations.size}-$mutationToken"
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = mutations.map { mutation ->
                            mutation.copy(resourceId = mutation.resourceId ?: "bookmark-ack")
                        } + SyncMutation(
                            resource = "NOTE",
                            resourceId = "note-ack",
                            mutation = Mutation.CREATED,
                            data = null,
                            timestamp = null
                        )
                    )
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "mark-in-flight-BOOKMARK",
                "mutations-BOOKMARK",
                "push-1-10"
            ),
            events
        )
    }

    @Test
    fun `mismatched pushed acknowledgement preserves in-flight markers for replay`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "COLLECTION_BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { _, mutationToken, _ ->
                    events += "push-1-$mutationToken"
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = listOf(
                            SyncMutation(
                                resource = "COLLECTION_BOOKMARK",
                                resourceId = "wrong-collection-wrong-bookmark",
                                mutation = Mutation.CREATED,
                                data = null,
                                timestamp = null
                            )
                        )
                    )
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-COLLECTION_BOOKMARK",
                "mark-in-flight-COLLECTION_BOOKMARK",
                "mutations-COLLECTION_BOOKMARK",
                "push-1-10"
            ),
            events
        )
    }

    @Test
    fun `sync finalizer failure happens after resource completion`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken, _ ->
                    events += "push-${mutations.size}-$mutationToken"
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.recordingAck(index)
                        }
                    )
                },
                completeSync = { token ->
                    events += "sync-complete-$token"
                    throw IllegalStateException("token store failed")
                }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "push-1-10",
                "complete-BOOKMARK-1",
                "sync-complete-11"
            ),
            events
        )
    }

    @Test
    fun `single pushed plan finalizes accepted post token`() = runTest {
        var completedToken: Long? = null

        executeDependencyAwareSync(
            resourceAdapters = listOf(
                RecordingAdapter(
                    resourceName = "BOOKMARK",
                    events = mutableListOf()
                )
            ),
            initialLastModificationDate = 1L,
            remoteResponse = MutationsResponse(
                lastModificationDate = 10L,
                mutations = emptyList()
            ),
            pushMutations = { mutations, _, _ ->
                MutationsResponse(
                    lastModificationDate = 25L,
                    mutations = mutations.mapIndexed { index, mutation ->
                        mutation.recordingAck(index)
                    }
                )
            },
            completeSync = { token -> completedToken = token }
        )

        assertEquals(25L, completedToken)
    }

    @Test
    fun `multiple pushed plans finalize initial get token`() = runTest {
        var completedToken: Long? = null

        executeDependencyAwareSync(
            resourceAdapters = listOf(
                RecordingAdapter(
                    resourceName = "BOOKMARK",
                    events = mutableListOf()
                ),
                RecordingAdapter(
                    resourceName = "COLLECTION",
                    events = mutableListOf()
                )
            ),
            initialLastModificationDate = 1L,
            remoteResponse = MutationsResponse(
                lastModificationDate = 10L,
                mutations = emptyList()
            ),
            pushMutations = { mutations, _, _ ->
                MutationsResponse(
                    lastModificationDate = 25L,
                    mutations = mutations.mapIndexed { index, mutation ->
                        mutation.recordingAck(index)
                    }
                )
            },
            completeSync = { token -> completedToken = token }
        )

        assertEquals(10L, completedToken)
    }

    @Test
    fun `local create mutations are marked in-flight before push`() = runTest {
        val events = mutableListOf<String>()

        executeDependencyAwareSync(
            resourceAdapters = listOf(
                RecordingAdapter(
                    resourceName = "COLLECTION_BOOKMARK",
                    events = events,
                    recordPlanLifecycleEvents = true
                )
            ),
            initialLastModificationDate = 1L,
            remoteResponse = MutationsResponse(
                lastModificationDate = 10L,
                mutations = emptyList()
            ),
            pushMutations = { mutations, mutationToken, _ ->
                events += "push-${mutations.size}-$mutationToken"
                MutationsResponse(
                    lastModificationDate = mutationToken + 1,
                    mutations = mutations.mapIndexed { index, mutation ->
                        mutation.recordingAck(index)
                    }
                )
            },
            completeSync = { token -> events += "sync-complete-$token" }
        )

        assertEquals(
            listOf(
                "build-COLLECTION_BOOKMARK",
                "mark-in-flight-COLLECTION_BOOKMARK",
                "mutations-COLLECTION_BOOKMARK",
                "push-1-10",
                "complete-COLLECTION_BOOKMARK-1",
                "sync-complete-11"
            ),
            events
        )
    }

    @Test
    fun `generic post failure preserves in-flight local create markers for replay`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "COLLECTION_BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { _, _, _ ->
                    events += "push-failed"
                    throw IllegalStateException("network failed before acceptance")
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-COLLECTION_BOOKMARK",
                "mark-in-flight-COLLECTION_BOOKMARK",
                "mutations-COLLECTION_BOOKMARK",
                "push-failed"
            ),
            events
        )
    }

    @Test
    fun `cancelled post preserves in-flight local create markers before job drains`() = runTest {
        val events = mutableListOf<String>()
        val pushStarted = CompletableDeferred<Unit>()
        val pushCanFinish = CompletableDeferred<Unit>()

        val job = backgroundScope.launch {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "COLLECTION_BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true,
                        suspendRollbackBeforeRecording = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { _, _, _ ->
                    events += "push-started"
                    pushStarted.complete(Unit)
                    pushCanFinish.await()
                    error("push should be cancelled before it finishes")
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        pushStarted.await()
        job.cancelAndJoin()

        assertEquals(
            listOf(
                "build-COLLECTION_BOOKMARK",
                "mark-in-flight-COLLECTION_BOOKMARK",
                "mutations-COLLECTION_BOOKMARK",
                "push-started"
            ),
            events
        )
    }

    @Test
    fun `stale sync epoch before post rolls back in-flight local create markers`() = runTest {
        val events = mutableListOf<String>()
        var epochValid = false

        assertFailsWith<SyncOperationInvalidatedException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "COLLECTION_BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { _, _, _ ->
                    events += "push-unexpected"
                    error("push should not run after stale epoch")
                },
                checkSyncStillValid = {
                    if (!epochValid) {
                        throw SyncOperationInvalidatedException("stale epoch")
                    }
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-COLLECTION_BOOKMARK",
                "mark-in-flight-COLLECTION_BOOKMARK",
                "mutations-COLLECTION_BOOKMARK",
                "rollback-in-flight-COLLECTION_BOOKMARK"
            ),
            events
        )
    }

    @Test
    fun `stale sync epoch in final post preflight rolls back in-flight local create markers`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<SyncOperationInvalidatedException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "COLLECTION_BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { _, _, _ ->
                    events += "push-unexpected"
                    error("push should not run after stale epoch")
                },
                preparePush = {
                    throw SyncOperationInvalidatedException("stale epoch")
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-COLLECTION_BOOKMARK",
                "mark-in-flight-COLLECTION_BOOKMARK",
                "mutations-COLLECTION_BOOKMARK",
                "rollback-in-flight-COLLECTION_BOOKMARK"
            ),
            events
        )
    }

    @Test
    fun `stale sync epoch at post admission skips request and rolls back in-flight markers`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<SyncOperationInvalidatedException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "COLLECTION_BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                preparePush = { mutations ->
                    mutations.isNotEmpty()
                },
                pushMutations = { _, _, _ ->
                    throw SyncOperationInvalidatedException("stale epoch before POST")
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-COLLECTION_BOOKMARK",
                "mark-in-flight-COLLECTION_BOOKMARK",
                "mutations-COLLECTION_BOOKMARK",
                "rollback-in-flight-COLLECTION_BOOKMARK"
            ),
            events
        )
    }

    @Test
    fun `cancellation during in-flight marking rolls back before job drains`() = runTest {
        val events = mutableListOf<String>()
        val markStarted = CompletableDeferred<Unit>()
        val markCanFinish = CompletableDeferred<Unit>()

        val job = backgroundScope.launch {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "COLLECTION_BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true,
                        onMarkStarted = {
                            markStarted.complete(Unit)
                        },
                        onMarkCanFinish = {
                            markCanFinish.await()
                        }
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { _, _, _ ->
                    events += "push-unexpected"
                    error("push should not run after cancellation during marking")
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        markStarted.await()
        job.cancel()
        markCanFinish.complete(Unit)
        job.join()

        assertEquals(
            listOf(
                "build-COLLECTION_BOOKMARK",
                "mark-in-flight-COLLECTION_BOOKMARK",
                "rollback-in-flight-COLLECTION_BOOKMARK"
            ),
            events
        )
    }

    @Test
    fun `cancellation during resource completion records committed plan without rollback or token publish`() = runTest {
        val events = mutableListOf<String>()
        val completeStarted = CompletableDeferred<Unit>()
        val completeCanFinish = CompletableDeferred<Unit>()

        val job = backgroundScope.launch {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true,
                        onCompleteStarted = {
                            completeStarted.complete(Unit)
                        },
                        onCompleteCanFinish = {
                            completeCanFinish.await()
                        }
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken, _ ->
                    events += "push-${mutations.size}-$mutationToken"
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.recordingAck(index)
                        }
                    )
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        completeStarted.await()
        job.cancel()
        completeCanFinish.complete(Unit)
        job.join()

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "mark-in-flight-BOOKMARK",
                "mutations-BOOKMARK",
                "push-1-10",
                "complete-BOOKMARK-1"
            ),
            events
        )
    }

    @Test
    fun `top level out of sync post failure preserves in-flight markers for replay`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<SyncNetworkException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { _, _, _ ->
                    events += "post-out-of-sync"
                    throw SyncNetworkException(
                        HttpStatusCode.Conflict,
                        """{"success":false,"error":{"code":"OutOfSyncError","message":"Invalid lastMutationAt"}}""",
                        "Invalid lastMutationAt"
                    )
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "mark-in-flight-BOOKMARK",
                "mutations-BOOKMARK",
                "post-out-of-sync"
            ),
            events
        )
    }

    @Test
    fun `backend shaped out of sync post failure preserves in-flight markers for replay`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<SyncNetworkException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { _, _, _ ->
                    events += "post-out-of-sync"
                    throw SyncNetworkException(
                        HttpStatusCode.Conflict,
                        """
                        {
                          "message": "Invalid lastMutationAt",
                          "type": "Conflict",
                          "success": false,
                          "details": {
                            "success": false,
                            "error": {
                              "code": "OutOfSyncError",
                              "message": "Invalid lastMutationAt"
                            }
                          }
                        }
                        """.trimIndent(),
                        "Invalid lastMutationAt"
                    )
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "mark-in-flight-BOOKMARK",
                "mutations-BOOKMARK",
                "post-out-of-sync"
            ),
            events
        )
    }

    @Test
    fun `generic conflict post failure preserves in-flight markers for replay`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<SyncNetworkException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { _, _, _ ->
                    events += "post-conflict"
                    throw SyncNetworkException(
                        HttpStatusCode.Conflict,
                        """{"success":false,"error":{"code":"VersionConflict","message":"Conflict"}}""",
                        "Conflict"
                    )
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "mark-in-flight-BOOKMARK",
                "mutations-BOOKMARK",
                "post-conflict"
            ),
            events
        )
    }

    @Test
    fun `stale sync epoch after accepted push does not apply acknowledgements or advance token`() = runTest {
        val events = mutableListOf<String>()
        var epochValid = true

        assertFailsWith<SyncOperationInvalidatedException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken, _ ->
                    events += "push-${mutations.size}-$mutationToken"
                    epochValid = false
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.recordingAck(index)
                        }
                    )
                },
                checkSyncStillValid = {
                    if (!epochValid) {
                        throw SyncOperationInvalidatedException("stale epoch")
                    }
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "mark-in-flight-BOOKMARK",
                "mutations-BOOKMARK",
                "push-1-10"
            ),
            events
        )
    }

    @Test
    fun `stale sync epoch before resource completion does not apply acknowledgements or rollback`() = runTest {
        val events = mutableListOf<String>()
        var pushReturned = false

        assertFailsWith<SyncOperationInvalidatedException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken, _ ->
                    events += "push-${mutations.size}-$mutationToken"
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.recordingAck(index)
                        }
                    ).also {
                        pushReturned = true
                    }
                },
                checkSyncStillValid = {
                    if (pushReturned) {
                        throw SyncOperationInvalidatedException("stale epoch")
                    }
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "mark-in-flight-BOOKMARK",
                "mutations-BOOKMARK",
                "push-1-10"
            ),
            events
        )
    }

    @Test
    fun `stale sync epoch at resource write boundary does not apply acknowledgements or rollback`() = runTest {
        val events = mutableListOf<String>()
        var boundaryChecks = 0

        assertFailsWith<SyncOperationInvalidatedException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events,
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken, _ ->
                    events += "push-${mutations.size}-$mutationToken"
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.recordingAck(index)
                        }
                    )
                },
                checkSyncStillValid = {
                    boundaryChecks += 1
                    if (boundaryChecks >= 3) {
                        throw SyncOperationInvalidatedException("stale epoch")
                    }
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "mark-in-flight-BOOKMARK",
                "mutations-BOOKMARK",
                "push-1-10"
            ),
            events
        )
    }

    @Test
    fun `stale sync epoch before pre dependency completion does not apply acknowledgements or rollback`() = runTest {
        val events = mutableListOf<String>()
        var pushReturned = false

        assertFailsWith<SyncOperationInvalidatedException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "COLLECTION_BOOKMARK",
                        events = events,
                        preDependencyDeletionMutation = SyncMutation(
                            resource = "COLLECTION_BOOKMARK",
                            resourceId = "remote-collection-bookmark-1",
                            mutation = Mutation.DELETED,
                            data = null,
                            timestamp = null
                        ),
                        recordPlanLifecycleEvents = true
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken, _ ->
                    events += "push-${mutations.size}-$mutationToken"
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.recordingAck(index)
                        }
                    ).also {
                        pushReturned = true
                    }
                },
                checkSyncStillValid = {
                    if (pushReturned) {
                        throw SyncOperationInvalidatedException("stale epoch")
                    }
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-pre-COLLECTION_BOOKMARK",
                "mark-in-flight-pre-COLLECTION_BOOKMARK",
                "mutations-pre-COLLECTION_BOOKMARK",
                "push-1-10"
            ),
            events
        )
    }

    @Test
    fun `stale sync epoch before finalizer does not publish token`() = runTest {
        val events = mutableListOf<String>()
        var epochValid = true

        assertFailsWith<SyncOperationInvalidatedException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events,
                        onComplete = {
                            epochValid = false
                        }
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken, _ ->
                    events += "push-${mutations.size}-$mutationToken"
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.recordingAck(index)
                        }
                    )
                },
                checkSyncStillValid = {
                    if (!epochValid) {
                        throw SyncOperationInvalidatedException("stale epoch")
                    }
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "push-1-10",
                "complete-BOOKMARK-1"
            ),
            events
        )
    }

    @Test
    fun `stale sync epoch at token finalization boundary does not publish token`() = runTest {
        val events = mutableListOf<String>()
        var completedResource = false

        assertFailsWith<SyncOperationInvalidatedException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events,
                        onComplete = {
                            completedResource = true
                        }
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken, _ ->
                    events += "push-${mutations.size}-$mutationToken"
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.recordingAck(index)
                        }
                    )
                },
                checkSyncStillValid = {
                    if (completedResource) {
                        throw SyncOperationInvalidatedException("stale epoch")
                    }
                },
                completeSync = { token -> events += "sync-complete-$token" }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "push-1-10",
                "complete-BOOKMARK-1"
            ),
            events
        )
    }

    @Test
    fun `stale sync epoch at finalizer write boundary does not publish token`() = runTest {
        val events = mutableListOf<String>()
        var finalizerStarted = false

        assertFailsWith<SyncOperationInvalidatedException> {
            executeDependencyAwareSync(
                resourceAdapters = listOf(
                    RecordingAdapter(
                        resourceName = "BOOKMARK",
                        events = events
                    )
                ),
                initialLastModificationDate = 1L,
                remoteResponse = MutationsResponse(
                    lastModificationDate = 10L,
                    mutations = emptyList()
                ),
                pushMutations = { mutations, mutationToken, _ ->
                    events += "push-${mutations.size}-$mutationToken"
                    MutationsResponse(
                        lastModificationDate = 11L,
                        mutations = mutations.mapIndexed { index, mutation ->
                            mutation.recordingAck(index)
                        }
                    )
                },
                completeSync = { token ->
                    finalizerStarted = true
                    checkCurrentSyncWriteBoundary()
                    events += "sync-complete-$token"
                },
                checkSyncStillValid = {
                    if (finalizerStarted) {
                        throw SyncOperationInvalidatedException("stale epoch")
                    }
                }
            )
        }

        assertEquals(
            listOf(
                "build-BOOKMARK",
                "push-1-10",
                "complete-BOOKMARK-1"
            ),
            events
        )
    }

    @Test
    fun `marker no-op creates are excluded from post body`() = runTest {
        val readingCreate = LocalModelMutation<SyncBookmark>(
            model = SyncBookmark.AyahBookmark(
                id = "local-reading",
                sura = 2,
                ayah = 255,
                isReading = true,
                lastModified = Instant.fromEpochMilliseconds(1000)
            ),
            remoteID = null,
            localID = "local-reading",
            mutation = Mutation.CREATED,
            ack = LocalMutationAck(
                localID = "local-reading",
                resource = LocalMutationResource.BOOKMARK,
                facet = LOCAL_MUTATION_BOOKMARK_READING_FACET,
                observedPendingOp = Mutation.CREATED,
                observedPendingVersion = 1
            )
        )
        val defaultCreate = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "default-collection",
                sura = 2,
                ayah = 255,
                lastModified = Instant.fromEpochMilliseconds(1001),
                bookmarkId = "remote-bookmark-default"
            ),
            remoteID = null,
            localID = "default-local-bookmark",
            mutation = Mutation.CREATED,
            ack = LocalMutationAck(
                localID = "default-local-bookmark",
                resource = LocalMutationResource.COLLECTION_BOOKMARK,
                facet = LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET,
                observedPendingOp = Mutation.CREATED,
                observedPendingVersion = 1
            )
        )
        val customCreate = LocalModelMutation<SyncCollectionBookmark>(
            model = SyncCollectionBookmark.AyahBookmark(
                collectionId = "remote-collection",
                sura = 3,
                ayah = 7,
                lastModified = Instant.fromEpochMilliseconds(1002),
                bookmarkId = "remote-bookmark-custom"
            ),
            remoteID = null,
            localID = "custom-link",
            mutation = Mutation.CREATED,
            ack = LocalMutationAck(
                localID = "custom-link",
                resource = LocalMutationResource.COLLECTION_BOOKMARK,
                facet = LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET,
                observedPendingOp = Mutation.CREATED,
                observedPendingVersion = 1
            )
        )
        val pushedMutations = mutableListOf<List<SyncMutation>>()

        executeDependencyAwareSync(
            resourceAdapters = listOf(
                bookmarkAdapter(readingCreate),
                collectionBookmarksAdapter(defaultCreate, customCreate)
            ),
            initialLastModificationDate = 1L,
            remoteResponse = MutationsResponse(
                lastModificationDate = 10L,
                mutations = emptyList()
            ),
            pushMutations = { mutations, mutationToken, _ ->
                pushedMutations += mutations
                MutationsResponse(
                    lastModificationDate = mutationToken + 1,
                    mutations = mutations
                )
            }
        )

        assertEquals(listOf(emptyList(), emptyList()), pushedMutations)
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

private fun bookmarkAdapter(
    vararg localMutations: LocalModelMutation<SyncBookmark>
): BookmarksSyncAdapter =
    BookmarksSyncAdapter(
        BookmarksSynchronizationConfigurations(
            localDataFetcher = object : LocalDataFetcher<SyncBookmark> {
                override suspend fun fetchLocalMutations(lastModified: Long): List<LocalModelMutation<SyncBookmark>> =
                    localMutations.toList()

                override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                    remoteIDs.associateWith { true }

                override suspend fun fetchLocalModel(remoteId: String): SyncBookmark? = null

                override suspend fun markLocalMutationsInFlight(
                    localMutations: List<LocalModelMutation<SyncBookmark>>
                ): List<LocalMutationAck> = emptyList()
            },
            resultNotifier = object : ResultNotifier<SyncBookmark> {
                override suspend fun didSucceed(
                    newToken: Long,
                    newRemoteMutations: List<RemoteModelMutation<SyncBookmark>>,
                    processedLocalMutations: List<LocalModelMutation<SyncBookmark>>
                ) = Unit

                override suspend fun didFail(message: String) {
                    fail("didFail called: $message")
                }
            },
            localModificationDateFetcher = object : LocalModificationDateFetcher {
                override suspend fun localLastModificationDate(): Long? = 0L
            }
        )
    )

private fun collectionBookmarksAdapter(
    vararg localMutations: LocalModelMutation<SyncCollectionBookmark>
): CollectionBookmarksSyncAdapter =
    CollectionBookmarksSyncAdapter(
        CollectionBookmarksSynchronizationConfigurations(
            localDataFetcher = object : LocalDataFetcher<SyncCollectionBookmark> {
                override suspend fun fetchLocalMutations(
                    lastModified: Long
                ): List<LocalModelMutation<SyncCollectionBookmark>> = localMutations.toList()

                override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                    remoteIDs.associateWith { true }

                override suspend fun fetchLocalModel(remoteId: String): SyncCollectionBookmark? = null

                override suspend fun markLocalMutationsInFlight(
                    localMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                ): List<LocalMutationAck> = emptyList()
            },
            resultNotifier = object : ResultNotifier<SyncCollectionBookmark> {
                override suspend fun didSucceed(
                    newToken: Long,
                    newRemoteMutations: List<RemoteModelMutation<SyncCollectionBookmark>>,
                    processedLocalMutations: List<LocalModelMutation<SyncCollectionBookmark>>
                ) = Unit

                override suspend fun didFail(message: String) {
                    fail("didFail called: $message")
                }
            },
            localModificationDateFetcher = object : LocalModificationDateFetcher {
                override suspend fun localLastModificationDate(): Long? = 0L
            }
        )
    )

private class RecordingAdapter(
    override val resourceName: String,
    private val events: MutableList<String>,
    private val onBuild: () -> Unit = {},
    private val onComplete: () -> Unit = {},
    private val preDependencyDeletionMutation: SyncMutation? = null,
    private val onPreDependencyDeletionComplete: () -> Unit = {},
    private val recordPlanLifecycleEvents: Boolean = false,
    private val suspendRollbackBeforeRecording: Boolean = false,
    private val onMarkStarted: () -> Unit = {},
    private val onMarkCanFinish: suspend () -> Unit = {},
    private val onCompleteStarted: () -> Unit = {},
    private val onCompleteCanFinish: suspend () -> Unit = {}
) : SyncResourceAdapter, PreDependencyDeletionSyncResourceAdapter {
    override val localModificationDateFetcher: LocalModificationDateFetcher =
        object : LocalModificationDateFetcher {
            override suspend fun localLastModificationDate(): Long = 0L
        }

    override suspend fun buildPreDependencyDeletionPlan(
        lastModificationDate: Long,
        remoteMutations: List<SyncMutation>
    ): ResourceSyncPlan? {
        val mutation = preDependencyDeletionMutation ?: return null
        events += "build-pre-$resourceName"
        return RecordingPlan(
            resourceName = resourceName,
            events = events,
            onComplete = onPreDependencyDeletionComplete,
            eventName = "pre-$resourceName",
            mutation = mutation,
            recordLifecycleEvents = recordPlanLifecycleEvents,
            suspendRollbackBeforeRecording = suspendRollbackBeforeRecording,
            onMarkStarted = onMarkStarted,
            onMarkCanFinish = onMarkCanFinish,
            onCompleteStarted = onCompleteStarted,
            onCompleteCanFinish = onCompleteCanFinish
        )
    }

    override suspend fun buildPlan(
        lastModificationDate: Long,
        remoteMutations: List<SyncMutation>
    ): ResourceSyncPlan {
        events += "build-$resourceName"
        onBuild()
        return RecordingPlan(
            resourceName = resourceName,
            events = events,
            onComplete = onComplete,
            recordLifecycleEvents = recordPlanLifecycleEvents,
            suspendRollbackBeforeRecording = suspendRollbackBeforeRecording,
            onMarkStarted = onMarkStarted,
            onMarkCanFinish = onMarkCanFinish,
            onCompleteStarted = onCompleteStarted,
            onCompleteCanFinish = onCompleteCanFinish
        )
    }

    override suspend fun didFail(message: String) = Unit
}

private class RecordingPlan(
    override val resourceName: String,
    private val events: MutableList<String>,
    private val onComplete: () -> Unit,
    private val eventName: String = resourceName,
    private val mutation: SyncMutation = defaultRecordingMutation(resourceName),
    private val recordLifecycleEvents: Boolean = false,
    private val suspendRollbackBeforeRecording: Boolean = false,
    private val onMarkStarted: () -> Unit = {},
    private val onMarkCanFinish: suspend () -> Unit = {},
    private val onCompleteStarted: () -> Unit = {},
    private val onCompleteCanFinish: suspend () -> Unit = {}
) : ResourceSyncPlan {
    override suspend fun mutationsToPush(): List<SyncMutation> {
        if (recordLifecycleEvents) {
            events += "mutations-$eventName"
        }
        return listOf(mutation)
    }

    override suspend fun markMutationsInFlight() {
        if (recordLifecycleEvents) {
            events += "mark-in-flight-$eventName"
        }
        onMarkStarted()
        onMarkCanFinish()
    }

    override suspend fun rollbackMutationsInFlight() {
        if (suspendRollbackBeforeRecording) {
            yield()
        }
        if (recordLifecycleEvents) {
            events += "rollback-in-flight-$eventName"
        }
    }

    override suspend fun complete(newToken: Long, pushedMutations: List<SyncMutation>) {
        events += "complete-$eventName-$newToken"
        onCompleteStarted()
        onCompleteCanFinish()
        onComplete()
    }
}

private fun defaultRecordingMutation(resourceName: String): SyncMutation {
    val data = if (resourceName.equals("COLLECTION_BOOKMARK", ignoreCase = true)) {
        buildJsonObject {
            put("collectionId", "recording-collection")
            put("bookmarkId", "recording-bookmark")
        }
    } else {
        null
    }
    return SyncMutation(
        resource = resourceName,
        resourceId = null,
        mutation = Mutation.CREATED,
        data = data,
        timestamp = null
    )
}

private fun SyncMutation.recordingAck(index: Int): SyncMutation {
    val remoteId = if (resource.equals("COLLECTION_BOOKMARK", ignoreCase = true) &&
        mutation == Mutation.CREATED &&
        resourceId == null
    ) {
        val collectionId = data?.get("collectionId")?.jsonPrimitive?.contentOrNull
        val bookmarkId = data?.get("bookmarkId")?.jsonPrimitive?.contentOrNull
            ?: data?.get("bookmark_id")?.jsonPrimitive?.contentOrNull
        if (!collectionId.isNullOrEmpty() && !bookmarkId.isNullOrEmpty()) {
            collectionBookmarkRemoteId(collectionId, bookmarkId)
        } else {
            "${resource.lowercase()}-$index"
        }
    } else {
        resourceId ?: "${resource.lowercase()}-$index"
    }
    return copy(resourceId = remoteId)
}

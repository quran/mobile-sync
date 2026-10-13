package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.NoteAyah
import com.quran.shared.syncengine.model.NoteRange
import com.quran.shared.syncengine.model.SyncNote
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NotesSyncAdapterTest {

    @Test
    fun `pushed note body is HTML-escaped for the server's sanitizer`() = runTest {
        val adapter = adapter(localMutations = listOf(localNote("Tom & Jerry: x<y, <b>bold</b>, &amp;")))

        val pushed = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()

        assertEquals(
            "Tom &amp; Jerry: x&lt;y, &lt;b&gt;bold&lt;/b&gt;, &amp;amp;",
            (pushed.data?.get("body") as JsonPrimitive).content
        )
    }

    @Test
    fun `remote note body is unescaped once`() = runTest {
        val persisted = mutableListOf<RemoteModelMutation<SyncNote>>()
        val adapter = adapter(localMutations = emptyList(), onDidSucceed = { persisted += it })

        adapter.buildPlan(0L, listOf(remoteNote("Tom &amp; Jerry: x&lt;y, &amp;lt;b&amp;gt;")))
            .complete(newToken = 10L, pushedMutations = emptyList())

        assertEquals("Tom & Jerry: x<y, &lt;b&gt;", persisted.single().model.body)
    }

    @Test
    fun `this device's echoed note matches its pending create`() = runTest {
        val local = localNote("Tom & Jerry")
        val adapter = adapter(localMutations = listOf(local))

        val plan = adapter.buildPlan(0L, listOf(remoteNote("Tom &amp; Jerry")))

        assertEquals(emptyList(), plan.mutationsToPush())
    }

    private fun localNote(body: String, localId: String = "local-1", remoteId: String? = null) = LocalModelMutation(
        model = SyncNote(
            id = localId,
            body = body,
            ranges = listOf(NoteRange(NoteAyah(2, 255), NoteAyah(2, 255))),
            lastModified = Instant.fromEpochMilliseconds(1_000),
            createdAt = Instant.fromEpochMilliseconds(1_000)
        ),
        remoteID = remoteId,
        localID = localId,
        mutation = if (remoteId == null) Mutation.CREATED else Mutation.MODIFIED
    )

    private fun remoteNote(body: String) = SyncMutation(
        resource = "NOTE",
        resourceId = "remote-1",
        mutation = Mutation.CREATED,
        data = buildJsonObject {
            put("body", body)
            put("ranges", buildJsonArray { add(JsonPrimitive("2:255-2:255")) })
            put("clientCreatedAt", "1970-01-01T00:00:01Z")
            put("clientUpdatedAt", "1970-01-01T00:00:01Z")
        },
        timestamp = 1_000L
    )

    private fun adapter(
        localMutations: List<LocalModelMutation<SyncNote>>,
        onDidSucceed: (List<RemoteModelMutation<SyncNote>>) -> Unit = {}
    ) = NotesSyncAdapter(
        NotesSynchronizationConfigurations(
            localDataFetcher = object : LocalDataFetcher<SyncNote> {
                override suspend fun fetchLocalMutations(lastModified: Long) = localMutations

                override suspend fun checkLocalExistence(remoteIDs: List<String>): Map<String, Boolean> =
                    remoteIDs.associateWith { false }

                override suspend fun fetchLocalModel(remoteId: String): SyncNote? = null
            },
            resultNotifier = object : ResultNotifier<SyncNote> {
                override suspend fun didSucceed(
                    newToken: Long,
                    newRemoteMutations: List<RemoteModelMutation<SyncNote>>,
                    processedLocalMutations: List<LocalModelMutation<SyncNote>>
                ) {
                    onDidSucceed(newRemoteMutations)
                }

                override suspend fun didFail(message: String) = Unit
            },
            localModificationDateFetcher = object : LocalModificationDateFetcher {
                override suspend fun localLastModificationDate(): Long? = 0L
            }
        )
    )
}

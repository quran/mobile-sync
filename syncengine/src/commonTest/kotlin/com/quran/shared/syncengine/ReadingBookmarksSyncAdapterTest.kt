@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.quran.shared.syncengine

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation
import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncReadingBookmark
import com.quran.shared.syncengine.model.SyncReadingBookmarkLocation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.time.Instant

class ReadingBookmarksSyncAdapterTest {
    @Test
    fun `page reading bookmark uses its dedicated resource`() = runTest {
        val adapter = adapterWithLocalMutations(
            listOf(
                LocalModelMutation(
                    model = SyncReadingBookmark(
                        slot = 2,
                        name = "Night reading",
                        location = SyncReadingBookmarkLocation.Page(page = 42),
                        lastModified = Instant.fromEpochMilliseconds(1000),
                        createdAt = Instant.fromEpochMilliseconds(500)
                    ),
                    remoteID = null,
                    localID = "2",
                    mutation = Mutation.CREATED
                )
            )
        )

        val mutation = adapter.buildPlan(0L, emptyList()).mutationsToPush().single()
        val data = assertNotNull(mutation.data)

        assertEquals("READING_BOOKMARK", mutation.resource)
        assertEquals(2, data["slot"]?.jsonPrimitive?.int)
        assertEquals("page", data["type"]?.jsonPrimitive?.content)
        assertEquals(42, data["key"]?.jsonPrimitive?.int)
        assertEquals(1, data["mushafId"]?.jsonPrimitive?.int)
        assertEquals("Night reading", data["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `remote ayah and page locations are parsed`() = runTest {
        var captured = listOf<RemoteModelMutation<SyncReadingBookmark>>()
        val adapter = adapterWithLocalMutations(emptyList()) { captured = it }

        adapter.buildPlan(
            0L,
            listOf(
                readingMutation("ayah-id", 1, "ayah", 5, 2),
                readingMutation("page-id", 3, "page", 88, null)
            )
        ).complete(1L, emptyList())

        assertEquals(
            SyncReadingBookmarkLocation.Ayah(sura = 5, ayah = 2),
            captured.first { it.remoteID == "ayah-id" }.model.location
        )
        assertEquals(
            SyncReadingBookmarkLocation.Page(page = 88),
            captured.first { it.remoteID == "page-id" }.model.location
        )
    }

    @Test
    fun `remote page location accepts backend mushaf id`() = runTest {
        var captured = listOf<RemoteModelMutation<SyncReadingBookmark>>()
        val adapter = adapterWithLocalMutations(emptyList()) { captured = it }

        adapter.buildPlan(
            0L,
            listOf(readingMutation("page-id", 3, "page", 2, null, mushafId = 5))
        ).complete(1L, emptyList())

        assertEquals(
            SyncReadingBookmarkLocation.Page(page = 2),
            captured.single().model.location
        )
    }

    @Test
    fun `unsupported location and invalid slot are ignored`() = runTest {
        var captured = listOf<RemoteModelMutation<SyncReadingBookmark>>()
        val adapter = adapterWithLocalMutations(emptyList()) { captured = it }

        adapter.buildPlan(
            0L,
            listOf(
                readingMutation("juz-id", 1, "juz", 2, null),
                readingMutation("slot-id", 4, "page", 20, null)
            )
        ).complete(1L, emptyList())

        assertEquals(emptyList(), captured)
    }

    @Test
    fun `newer local slot updates the existing remote slot`() = runTest {
        val local = LocalModelMutation(
            model = SyncReadingBookmark(
                slot = 1,
                name = null,
                location = SyncReadingBookmarkLocation.Page(page = 50),
                lastModified = Instant.fromEpochMilliseconds(2000),
                createdAt = Instant.fromEpochMilliseconds(500)
            ),
            remoteID = null,
            localID = "1",
            mutation = Mutation.CREATED
        )
        val adapter = adapterWithLocalMutations(listOf(local))

        val pushed = adapter.buildPlan(
            0L,
            listOf(readingMutation("remote-slot-1", 1, "page", 40, null))
        ).mutationsToPush().single()

        assertEquals("remote-slot-1", pushed.resourceId)
        assertEquals(Mutation.MODIFIED, pushed.mutation)
        assertEquals(50, pushed.data?.get("key")?.jsonPrimitive?.int)
    }

    @Test
    fun `malformed typed location is ignored instead of clearing its slot`() = runTest {
        var captured = listOf<RemoteModelMutation<SyncReadingBookmark>>()
        val adapter = adapterWithLocalMutations(emptyList()) { captured = it }
        val malformed = SyncMutation(
            resource = "READING_BOOKMARK",
            resourceId = "malformed",
            mutation = Mutation.MODIFIED,
            data = buildJsonObject {
                put("slot", 1)
                put("type", "ayah")
                put("key", 2)
                put("mushafId", 1)
            },
            timestamp = 1000
        )

        adapter.buildPlan(0L, listOf(malformed)).complete(1L, emptyList())

        assertEquals(emptyList(), captured)
    }

    private fun readingMutation(
        id: String,
        slot: Int,
        type: String,
        key: Int,
        verseNumber: Int?,
        mushafId: Int = 1
    ) =
        SyncMutation(
            resource = "READING_BOOKMARK",
            resourceId = id,
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("slot", slot)
                put("type", type)
                put("key", key)
                verseNumber?.let { put("verseNumber", it) }
                put("mushafId", mushafId)
            },
            timestamp = 1000
        )

    private fun adapterWithLocalMutations(
        localMutations: List<LocalModelMutation<SyncReadingBookmark>>,
        onRemote: (List<RemoteModelMutation<SyncReadingBookmark>>) -> Unit = {}
    ) = ReadingBookmarksSyncAdapter(
        ReadingBookmarksSynchronizationConfigurations(
            localDataFetcher = object : LocalDataFetcher<SyncReadingBookmark> {
                override suspend fun fetchLocalMutations(lastModified: Long) = localMutations
                override suspend fun checkLocalExistence(remoteIDs: List<String>) = remoteIDs.associateWith { true }
                override suspend fun fetchLocalModel(remoteId: String): SyncReadingBookmark? = null
            },
            resultNotifier = object : ResultNotifier<SyncReadingBookmark> {
                override suspend fun didSucceed(
                    newToken: Long,
                    newRemoteMutations: List<RemoteModelMutation<SyncReadingBookmark>>,
                    processedLocalMutations: List<LocalModelMutation<SyncReadingBookmark>>
                ) = onRemote(newRemoteMutations)

                override suspend fun didFail(message: String) = fail(message)
            },
            localModificationDateFetcher = object : LocalModificationDateFetcher {
                override suspend fun localLastModificationDate(): Long = 0L
            }
        )
    )
}

package com.quran.shared.syncengine

import com.quran.shared.mutations.Mutation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PushedMutationAckValidationTest {

    @Test
    fun `collection bookmark ACK rejects disagreeing bookmark id aliases`() {
        assertFailsWith<IllegalStateException> {
            validatePushedMutationResponse(
                requestedMutations = listOf(collectionBookmarkCreateRequest(bookmarkId = null)),
                responseMutations = listOf(
                    collectionBookmarkCreateResponse {
                        put("collectionId", "collection-a")
                        put("bookmarkId", "bookmark-a")
                        put("bookmark_id", "bookmark-b")
                    }
                )
            )
        }
    }

    @Test
    fun `collection bookmark ACK rejects snake alias conflicting with request when camel alias matches`() {
        assertFailsWith<IllegalStateException> {
            validatePushedMutationResponse(
                requestedMutations = listOf(collectionBookmarkCreateRequest(bookmarkId = "bookmark-a")),
                responseMutations = listOf(
                    collectionBookmarkCreateResponse {
                        put("collectionId", "collection-a")
                        put("bookmarkId", "bookmark-a")
                        put("bookmark_id", "bookmark-b")
                    }
                )
            )
        }
    }

    @Test
    fun `collection bookmark ACK rejects blank snake alias when camel alias has value`() {
        assertFailsWith<IllegalStateException> {
            validatePushedMutationResponse(
                requestedMutations = listOf(collectionBookmarkCreateRequest(bookmarkId = "bookmark-a")),
                responseMutations = listOf(
                    collectionBookmarkCreateResponse(resourceId = "") {
                        put("collectionId", "collection-a")
                        put("bookmarkId", "bookmark-a")
                        put("bookmark_id", "")
                    }
                )
            )
        }
    }

    @Test
    fun `collection bookmark ACK rejects present blank camel alias conflicting with request evidence`() {
        assertFailsWith<IllegalStateException> {
            validatePushedMutationResponse(
                requestedMutations = listOf(collectionBookmarkDeleteRequest()),
                responseMutations = listOf(
                    SyncMutation(
                        resource = "COLLECTION_BOOKMARK",
                        resourceId = "collection-a-bookmark-a",
                        mutation = Mutation.DELETED,
                        data = buildJsonObject {
                            put("collectionId", "collection-a")
                            put("bookmarkId", "")
                        },
                        timestamp = null
                    )
                )
            )
        }
    }

    @Test
    fun `collection bookmark ACK accepts absent bookmark alias when request evidence is nonblank`() {
        validatePushedMutationResponse(
            requestedMutations = listOf(collectionBookmarkDeleteRequest()),
            responseMutations = listOf(
                SyncMutation(
                    resource = "COLLECTION_BOOKMARK",
                    resourceId = "collection-a-bookmark-a",
                    mutation = Mutation.DELETED,
                    data = buildJsonObject {
                        put("collectionId", "collection-a")
                    },
                    timestamp = null
                )
            )
        )
    }

    @Test
    fun `collection bookmark ACK accepts matching bookmark id aliases`() {
        validatePushedMutationResponse(
            requestedMutations = listOf(collectionBookmarkCreateRequest(bookmarkId = "bookmark-a")),
            responseMutations = listOf(
                collectionBookmarkCreateResponse {
                    put("collectionId", "collection-a")
                    put("bookmarkId", "bookmark-a")
                    put("bookmark_id", "bookmark-a")
                }
            )
        )
    }

    @Test
    fun `collection bookmark ACK accepts absent snake alias when camel alias has value`() {
        validatePushedMutationResponse(
            requestedMutations = listOf(collectionBookmarkCreateRequest(bookmarkId = "bookmark-a")),
            responseMutations = listOf(
                collectionBookmarkCreateResponse(resourceId = "") {
                    put("collectionId", "collection-a")
                    put("bookmarkId", "bookmark-a")
                }
            )
        )
    }

    @Test
    fun `collection bookmark create ACK accepts missing resource id with relation evidence`() {
        validatePushedMutationResponse(
            requestedMutations = listOf(collectionBookmarkCreateRequest(bookmarkId = "bookmark-a")),
            responseMutations = listOf(
                collectionBookmarkCreateResponse {
                    put("collectionId", "collection-a")
                    put("bookmarkId", "bookmark-a")
                }
            )
        )
    }

    @Test
    fun `collection bookmark create ACK accepts blank resource id with relation evidence`() {
        validatePushedMutationResponse(
            requestedMutations = listOf(collectionBookmarkCreateRequest(bookmarkId = "bookmark-a")),
            responseMutations = listOf(
                collectionBookmarkCreateResponse(resourceId = "") {
                    put("collectionId", "collection-a")
                    put("bookmarkId", "bookmark-a")
                }
            )
        )
    }

    @Test
    fun `collection bookmark delete ACK rejects missing resource id even with relation evidence`() {
        assertFailsWith<IllegalStateException> {
            validatePushedMutationResponse(
                requestedMutations = listOf(collectionBookmarkDeleteRequest()),
                responseMutations = listOf(
                    SyncMutation(
                        resource = "COLLECTION_BOOKMARK",
                        resourceId = null,
                        mutation = Mutation.DELETED,
                        data = buildJsonObject {
                            put("collectionId", "collection-a")
                            put("bookmarkId", "bookmark-a")
                        },
                        timestamp = null
                    )
                )
            )
        }
    }

    @Test
    fun `collection bookmark delete ACK rejects blank resource id even with relation evidence`() {
        assertFailsWith<IllegalStateException> {
            validatePushedMutationResponse(
                requestedMutations = listOf(collectionBookmarkDeleteRequest()),
                responseMutations = listOf(
                    SyncMutation(
                        resource = "COLLECTION_BOOKMARK",
                        resourceId = "",
                        mutation = Mutation.DELETED,
                        data = buildJsonObject {
                            put("collectionId", "collection-a")
                            put("bookmarkId", "bookmark-a")
                        },
                        timestamp = null
                    )
                )
            )
        }
    }

    @Test
    fun `non collection create ACK rejects missing resource id`() {
        assertFailsWith<IllegalStateException> {
            validatePushedMutationResponse(
                requestedMutations = listOf(
                    SyncMutation(
                        resource = "BOOKMARK",
                        resourceId = null,
                        mutation = Mutation.CREATED,
                        data = buildJsonObject {
                            put("key", 1)
                            put("verseNumber", 1)
                        },
                        timestamp = null
                    )
                ),
                responseMutations = listOf(
                    SyncMutation(
                        resource = "BOOKMARK",
                        resourceId = null,
                        mutation = Mutation.CREATED,
                        data = buildJsonObject {
                            put("key", 1)
                            put("verseNumber", 1)
                        },
                        timestamp = null
                    )
                )
            )
        }
    }

    @Test
    fun `simple resource create ACK rejects blank resource id`() {
        listOf("BOOKMARK", "COLLECTION", "NOTE", "READING_SESSION").forEach { resource ->
            assertFailsWith<IllegalStateException> {
                validatePushedMutationResponse(
                    requestedMutations = listOf(
                        SyncMutation(
                            resource = resource,
                            resourceId = null,
                            mutation = Mutation.CREATED,
                            data = buildJsonObject {
                                put("key", 1)
                                put("verseNumber", 1)
                            },
                            timestamp = null
                        )
                    ),
                    responseMutations = listOf(
                        SyncMutation(
                            resource = resource,
                            resourceId = "",
                            mutation = Mutation.CREATED,
                            data = buildJsonObject {
                                put("key", 1)
                                put("verseNumber", 1)
                            },
                            timestamp = null
                        )
                    )
                )
            }
        }
    }

    private fun collectionBookmarkCreateRequest(bookmarkId: String?): SyncMutation =
        SyncMutation(
            resource = "COLLECTION_BOOKMARK",
            resourceId = null,
            mutation = Mutation.CREATED,
            data = buildJsonObject {
                put("collectionId", "collection-a")
                bookmarkId?.let { put("bookmarkId", it) }
            },
            timestamp = null
        )

    private fun collectionBookmarkDeleteRequest(): SyncMutation =
        SyncMutation(
            resource = "COLLECTION_BOOKMARK",
            resourceId = "collection-a-bookmark-a",
            mutation = Mutation.DELETED,
            data = buildJsonObject {
                put("collectionId", "collection-a")
                put("bookmarkId", "bookmark-a")
            },
            timestamp = null
        )

    private fun collectionBookmarkCreateResponse(
        resourceId: String? = null,
        buildData: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
    ): SyncMutation =
        SyncMutation(
            resource = "COLLECTION_BOOKMARK",
            resourceId = resourceId,
            mutation = Mutation.CREATED,
            data = buildJsonObject(buildData),
            timestamp = null
        )
}

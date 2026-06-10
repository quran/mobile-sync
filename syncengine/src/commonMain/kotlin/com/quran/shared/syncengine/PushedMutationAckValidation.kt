package com.quran.shared.syncengine

import com.quran.shared.mutations.Mutation
import com.quran.shared.syncengine.model.collectionBookmarkRemoteId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun validatePushedMutationResponse(
    requestedMutations: List<SyncMutation>,
    responseMutations: List<SyncMutation>
) {
    validatePushedMutationCount(
        resourceName = "sync response",
        localCount = requestedMutations.size,
        pushedCount = responseMutations.size
    )

    requestedMutations.zip(responseMutations).forEachIndexed { index, (requestMutation, responseMutation) ->
        validatePushedMutationAck(
            resourceName = requestMutation.resource,
            index = index,
            expectedRemoteId = requestMutation.resourceId,
            expectedMutation = requestMutation.mutation,
            pushedMutation = responseMutation,
            acknowledgedRemoteId = responseMutation.acknowledgedRemoteIdFor(requestMutation)
        )
    }
}

internal fun validatePushedMutationCount(
    resourceName: String,
    localCount: Int,
    pushedCount: Int
) {
    if (localCount != pushedCount) {
        throw IllegalStateException(
            "Mismatched pushed mutation counts for $resourceName: local=$localCount, remote=$pushedCount"
        )
    }
}

internal fun validatePushedMutationAck(
    resourceName: String,
    index: Int,
    expectedRemoteId: String?,
    expectedMutation: Mutation,
    pushedMutation: SyncMutation,
    acknowledgedRemoteId: String?
): String {
    if (!pushedMutation.resource.equals(resourceName, ignoreCase = true)) {
        throw IllegalStateException(
            "Unexpected pushed mutation resource=${pushedMutation.resource} for $resourceName at index=$index"
        )
    }
    if (pushedMutation.mutation != expectedMutation) {
        throw IllegalStateException(
            "Mutation type mismatch at index=$index for $resourceName: " +
                "local=$expectedMutation, remote=${pushedMutation.mutation}"
        )
    }

    val remoteId = acknowledgedRemoteId
        ?: throw IllegalStateException("Missing resourceId for pushed mutation at index=$index for $resourceName")
    if (remoteId.isBlank()) {
        throw IllegalStateException("Missing resourceId for pushed mutation at index=$index for $resourceName")
    }
    if (expectedRemoteId != null && remoteId != expectedRemoteId) {
        throw IllegalStateException(
            "Pushed mutation remote id mismatch at index=$index for $resourceName: " +
                "local=$expectedRemoteId, remote=$remoteId"
        )
    }
    if (expectedRemoteId == null && expectedMutation != Mutation.CREATED) {
        throw IllegalStateException(
            "Missing local remote id for non-create pushed mutation at index=$index for $resourceName"
        )
    }
    return remoteId
}

internal fun SyncMutation.acknowledgedRemoteIdFor(requestMutation: SyncMutation): String? {
    if (!resource.equals("COLLECTION_BOOKMARK", ignoreCase = true)) {
        return resourceId?.takeIf { it.isNotBlank() }
    }

    val relation = collectionBookmarkRelationEvidence(requestMutation)
    val expectedRemoteId = collectionBookmarkRemoteIdOrNull(
        collectionId = relation.collectionId,
        bookmarkId = relation.bookmarkId
    )

    if (!resourceId.isNullOrBlank()) {
        if (requestMutation.resourceId != null) {
            return resourceId
        }
        if (expectedRemoteId == null) {
            if (requestMutation.mutation == Mutation.CREATED) {
                throw IllegalStateException(
                    "Unvalidated collection bookmark resourceId for create ACK: remote=$resourceId"
                )
            }
            return resourceId
        }
        if (resourceId != expectedRemoteId) {
            throw IllegalStateException(
                "Collection bookmark resourceId mismatch for ACK: expected=$expectedRemoteId, remote=$resourceId"
            )
        }
        return resourceId
    }

    if (requestMutation.mutation != Mutation.CREATED || mutation != Mutation.CREATED) {
        return null
    }

    return expectedRemoteId
}

private fun SyncMutation.collectionBookmarkRelationEvidence(
    requestMutation: SyncMutation
): CollectionBookmarkRelationEvidence {
    validateCollectionBookmarkResponseEvidence(requestMutation)
    val payloadBookmarkId = independentBookmarkIdEvidence(requestMutation)
    val collectionId = requestMutation.data.stringOrNull("collectionId")
        ?: data.stringOrNull("collectionId")
        ?: collectionIdFromCompositeId(requestMutation.resourceId, payloadBookmarkId)
        ?: resourceId.takeIf { !payloadBookmarkId.isNullOrEmpty() }
            ?.let { collectionIdFromCompositeId(it, payloadBookmarkId) }
    val bookmarkId = payloadBookmarkId
        ?: bookmarkIdFromCompositeId(requestMutation.resourceId, collectionId)
        ?: bookmarkIdFromCompositeId(resourceId, collectionId)
    return CollectionBookmarkRelationEvidence(collectionId = collectionId, bookmarkId = bookmarkId)
}

private fun SyncMutation.independentBookmarkIdEvidence(requestMutation: SyncMutation): String? =
    data.stringOrNull("bookmarkId")
        ?: data.stringOrNull("bookmark_id")
        ?: requestMutation.data.stringOrNull("bookmarkId")
        ?: requestMutation.data.stringOrNull("bookmark_id")

private fun SyncMutation.validateCollectionBookmarkResponseEvidence(requestMutation: SyncMutation) {
    requestMutation.data.requireConsistentBookmarkIdAliases("request")
    val responseData = data ?: return
    responseData.requireConsistentBookmarkIdAliases("response")
    requireMatchingEvidence(
        label = "collectionId",
        local = requestMutation.data.nonEmptyStringOrNull("collectionId"),
        remote = responseData.nonEmptyStringOrNull("collectionId")
    )
    requireMatchingBookmarkIdEvidence(requestMutation.data, responseData)
    requireMatchingEvidence(
        label = "key",
        local = requestMutation.data.intOrNull("key"),
        remote = responseData.intOrNull("key")
    )
    requireMatchingEvidence(
        label = "verseNumber",
        local = requestMutation.data.intOrNull("verseNumber"),
        remote = responseData.intOrNull("verseNumber")
    )

    val responseCollectionId = responseData.nonEmptyStringOrNull("collectionId")
    val responseBookmarkId = responseData.bookmarkIdOrNull()
    if (resourceId.isNullOrBlank()) {
        return
    }
    if (responseCollectionId != null && responseBookmarkId != null) {
        val responseDataRemoteId = collectionBookmarkRemoteId(responseCollectionId, responseBookmarkId)
        if (resourceId != responseDataRemoteId) {
            throw IllegalStateException(
                "Collection bookmark ACK relation mismatch: data=$responseDataRemoteId, resourceId=$resourceId"
            )
        }
        return
    }
    if (
        responseCollectionId != null &&
        bookmarkIdFromCompositeId(resourceId, responseCollectionId) == null
    ) {
        throw IllegalStateException(
            "Collection bookmark ACK collectionId mismatch: data=$responseCollectionId, resourceId=$resourceId"
        )
    }
    if (
        responseBookmarkId != null &&
        collectionIdFromCompositeId(resourceId, responseBookmarkId) == null
    ) {
        throw IllegalStateException(
            "Collection bookmark ACK bookmarkId mismatch: data=$responseBookmarkId, resourceId=$resourceId"
        )
    }
}

private fun requireMatchingEvidence(label: String, local: String?, remote: String?) {
    if (local != null && remote != null && local != remote) {
        throw IllegalStateException(
            "Collection bookmark ACK $label mismatch: local=$local, remote=$remote"
        )
    }
}

private fun requireMatchingEvidence(label: String, local: Int?, remote: Int?) {
    if (local != null && remote != null && local != remote) {
        throw IllegalStateException(
            "Collection bookmark ACK $label mismatch: local=$local, remote=$remote"
        )
    }
}

private fun collectionBookmarkRemoteIdOrNull(collectionId: String?, bookmarkId: String?): String? {
    if (collectionId.isNullOrBlank() || bookmarkId.isNullOrBlank()) {
        return null
    }
    return collectionBookmarkRemoteId(collectionId, bookmarkId)
}

private fun bookmarkIdFromCompositeId(resourceId: String?, collectionId: String?): String? {
    if (resourceId.isNullOrBlank() || collectionId.isNullOrBlank()) {
        return null
    }
    val prefix = "$collectionId-"
    return if (resourceId.startsWith(prefix) && resourceId.length > prefix.length) {
        resourceId.removePrefix(prefix)
    } else {
        null
    }
}

private fun collectionIdFromCompositeId(resourceId: String?, bookmarkId: String?): String? {
    if (resourceId.isNullOrBlank() || bookmarkId.isNullOrBlank()) {
        return null
    }
    val suffix = "-$bookmarkId"
    return if (resourceId.endsWith(suffix) && resourceId.length > suffix.length) {
        resourceId.removeSuffix(suffix)
    } else {
        null
    }
}

private data class CollectionBookmarkRelationEvidence(
    val collectionId: String?,
    val bookmarkId: String?
)

private data class BookmarkIdAliases(
    val camelCase: PresentString?,
    val snakeCase: PresentString?
) {
    val collapsed: String?
        get() = camelCase?.nonBlankValue ?: snakeCase?.nonBlankValue
}

private data class PresentString(
    val value: String?
) {
    val nonBlankValue: String?
        get() = value?.takeIf { it.isNotBlank() }
}

private fun JsonObject?.bookmarkIdOrNull(): String? =
    bookmarkIdAliases().collapsed

private fun JsonObject?.bookmarkIdAliases(): BookmarkIdAliases =
    BookmarkIdAliases(
        camelCase = presentStringOrNull("bookmarkId"),
        snakeCase = presentStringOrNull("bookmark_id")
    )

private fun JsonObject?.requireConsistentBookmarkIdAliases(source: String) {
    val aliases = bookmarkIdAliases()
    val camelCase = aliases.camelCase
    val snakeCase = aliases.snakeCase
    if (camelCase != null && snakeCase != null && camelCase.value != snakeCase.value) {
        throw IllegalStateException(
            "Collection bookmark ACK bookmarkId alias mismatch in $source: " +
                "bookmarkId=${camelCase.value}, bookmark_id=${snakeCase.value}"
        )
    }
}

private fun requireMatchingBookmarkIdEvidence(localData: JsonObject?, responseData: JsonObject?) {
    val local = localData.bookmarkIdAliases()
    val remote = responseData.bookmarkIdAliases()
    requireMatchingStringEvidence(
        label = "bookmarkId",
        local = local.camelCase,
        remote = remote.camelCase
    )
    requireMatchingStringEvidence(
        label = "bookmark_id",
        local = local.snakeCase,
        remote = remote.snakeCase
    )
    requireMatchingStringEvidence(
        label = "bookmarkId",
        local = local.camelCase,
        remote = remote.snakeCase
    )
    requireMatchingStringEvidence(
        label = "bookmark_id",
        local = local.snakeCase,
        remote = remote.camelCase
    )
}

private fun requireMatchingStringEvidence(label: String, local: PresentString?, remote: PresentString?) {
    if (local != null && remote != null && local.value != remote.value) {
        throw IllegalStateException(
            "Collection bookmark ACK $label mismatch: local=${local.value}, remote=${remote.value}"
        )
    }
}

private fun JsonObject?.nonEmptyStringOrNull(key: String): String? =
    stringOrNull(key)?.takeIf { it.isNotBlank() }

private fun JsonObject?.presentStringOrNull(key: String): PresentString? {
    val value = this?.get(key) ?: return null
    return PresentString(value = value.jsonPrimitive.contentOrNull)
}

private fun JsonObject?.stringOrNull(key: String): String? =
    this?.get(key)?.jsonPrimitive?.contentOrNull

private fun JsonObject?.intOrNull(key: String): Int? =
    this?.get(key)?.jsonPrimitive?.intOrNull

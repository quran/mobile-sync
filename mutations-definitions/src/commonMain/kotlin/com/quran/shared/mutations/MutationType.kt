package com.quran.shared.mutations

enum class Mutation {
    CREATED,
    DELETED,
    MODIFIED
}

enum class LocalMutationResource {
    BOOKMARK,
    COLLECTION,
    COLLECTION_BOOKMARK,
    NOTE,
    READING_SESSION
}

const val LOCAL_MUTATION_ENTITY_FACET = "ENTITY"
const val LOCAL_MUTATION_BOOKMARK_ENTITY_FACET = "BOOKMARK_ENTITY"
const val LOCAL_MUTATION_BOOKMARK_READING_FACET = "BOOKMARK_READING"
const val LOCAL_MUTATION_BOOKMARK_DEFAULT_FACET = "BOOKMARK_DEFAULT"
const val LOCAL_MUTATION_COLLECTION_BOOKMARK_LINK_FACET = "COLLECTION_BOOKMARK_LINK"

data class LocalMutationAck(
    val localID: String,
    val resource: LocalMutationResource,
    val facet: String,
    val observedPendingOp: Mutation,
    val observedPendingVersion: Long
)

class LocalModelMutation<Model>(
    val model: Model,
    val remoteID: String?,
    val localID: String,
    val mutation: Mutation,
    val ack: LocalMutationAck? = null
)

class RemoteModelMutation<Model>(
    val model: Model,
    val remoteID: String,
    val mutation: Mutation,
    val ack: LocalMutationAck? = null
)

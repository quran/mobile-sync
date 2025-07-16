package com.quran.shared.mutations

enum class Mutation {
    CREATED,
    DELETED,
    MODIFIED
}

class LocalModelMutation<Model>(val model: Model, val remoteID: String?, val localID: String, val mutation: Mutation)

class RemoteModelMutation<Model>(val model: Model, val remoteID: String, val mutation: Mutation)
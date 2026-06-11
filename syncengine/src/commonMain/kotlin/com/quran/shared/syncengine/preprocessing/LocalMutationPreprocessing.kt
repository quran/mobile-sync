package com.quran.shared.syncengine.preprocessing

import com.quran.shared.mutations.LocalModelMutation
import com.quran.shared.mutations.Mutation

internal fun <Model> List<LocalModelMutation<Model>>.requireRemoteBackedDeletes(
    resourceName: String
) {
    forEach { mutation ->
        if (mutation.mutation == Mutation.DELETED && mutation.remoteID == null) {
            throw IllegalArgumentException(
                "$resourceName deletion without remote ID is not allowed. " +
                    "Mutation: ${mutation.mutation}(${mutation.localID})"
            )
        }
    }
}

package com.quran.shared.syncengine

import com.quran.shared.mutations.Mutation
import kotlinx.serialization.json.JsonObject

data class SyncMutation(
    val resource: String,
    val resourceId: String?,
    val mutation: Mutation,
    val data: JsonObject?,
    val timestamp: Long?
)

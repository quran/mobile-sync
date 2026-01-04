package com.quran.shared.syncengine.model

import kotlin.time.Instant

data class SyncCollection(
    val id: String,
    val name: String?,
    val lastModified: Instant
)

internal sealed class SyncCollectionKey {
    data class Name(val name: String) : SyncCollectionKey() {
        override fun toString(): String = "name=$name"
    }

    data class LocalId(val localId: String) : SyncCollectionKey() {
        override fun toString(): String = "localId=$localId"
    }
}

internal fun SyncCollection.conflictKeyOrNull(): SyncCollectionKey? {
    return name?.let { SyncCollectionKey.Name(it) }
}

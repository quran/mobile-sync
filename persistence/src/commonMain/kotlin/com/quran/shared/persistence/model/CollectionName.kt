package com.quran.shared.persistence.model

import app.cash.sqldelight.ColumnAdapter
import com.quran.shared.mutations.collectionNameKey
import com.quran.shared.mutations.trimLikeServer
import kotlin.jvm.JvmInline

/** A collection name trimmed like the server. */
@JvmInline
value class CollectionName private constructor(val value: String) {
    val key: CollectionNameKey get() = CollectionNameKey.of(this)

    internal companion object {
        fun of(name: String): CollectionName = CollectionName(name.trimLikeServer())
    }

    internal object Adapter : ColumnAdapter<CollectionName, String> {
        override fun decode(databaseValue: String): CollectionName = of(databaseValue)
        override fun encode(value: CollectionName): String = value.value
    }
}

/** How the server tells collection names apart. */
@JvmInline
value class CollectionNameKey private constructor(val value: String) {
    internal companion object {
        fun of(name: CollectionName): CollectionNameKey = CollectionNameKey(collectionNameKey(name.value))
    }

    internal object Adapter : ColumnAdapter<CollectionNameKey, String> {
        override fun decode(databaseValue: String): CollectionNameKey = CollectionNameKey(databaseValue)
        override fun encode(value: CollectionNameKey): String = value.value
    }
}

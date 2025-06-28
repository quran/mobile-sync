package com.quran.shared.persistence.model

enum class BookmarkMutationType {
    CREATED,
    DELETED
}

data class BookmarkMutation(
    val page: Int,
    val remoteId: String? = null,
    val mutationType: BookmarkMutationType,
    val lastUpdated: Long
)
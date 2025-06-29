package com.quran.shared.persistence.model

enum class PageBookmarkMutationType {
    CREATED,
    DELETED
}

data class PageBookmarkMutation(
    val page: Int,
    val remoteId: String? = null,
    val mutationType: PageBookmarkMutationType,
    val lastUpdated: Long
)
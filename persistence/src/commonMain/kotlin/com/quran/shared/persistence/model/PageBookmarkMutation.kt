package com.quran.shared.persistence.model

enum class PageBookmarkMutationType {
    CREATED,
    DELETED
}

data class PageBookmarkMutation internal constructor(
    val page: Int,
    internal val localId: Long?,
    val remoteId: String? = null,
    val mutationType: PageBookmarkMutationType,
    val lastUpdated: Long
) {

    companion object {
        fun createRemoteMutation(
            page: Int,
            remoteId: String? = null,
            mutationType: PageBookmarkMutationType,
            lastUpdated: Long
        ): PageBookmarkMutation = PageBookmarkMutation(page, null, remoteId, mutationType, lastUpdated)
    }
}
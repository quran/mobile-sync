package com.quran.shared.syncengine.network

import com.quran.shared.syncengine.SyncMutation

data class MutationsResponse(
    val lastModificationDate: Long,
    val mutations: List<SyncMutation>,
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val hasMore: Boolean? = null,
    val nextCursor: String? = null,
    val syncUntil: Long? = null
)

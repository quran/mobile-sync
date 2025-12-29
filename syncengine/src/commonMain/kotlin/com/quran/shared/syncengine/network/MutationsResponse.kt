package com.quran.shared.syncengine.network

import com.quran.shared.mutations.RemoteModelMutation
import com.quran.shared.syncengine.model.SyncBookmark

data class MutationsResponse(
    val lastModificationDate: Long,
    val mutations: List<RemoteModelMutation<SyncBookmark>>
)

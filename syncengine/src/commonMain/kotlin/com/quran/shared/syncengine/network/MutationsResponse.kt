package com.quran.shared.syncengine.network

import com.quran.shared.syncengine.SyncMutation

data class MutationsResponse(
    val lastModificationDate: Long,
    val mutations: List<SyncMutation>
)

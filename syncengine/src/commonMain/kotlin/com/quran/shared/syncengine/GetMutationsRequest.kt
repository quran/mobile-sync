package com.quran.shared.syncengine

import com.quran.shared.mutations.RemoteModelMutation

class GetMutationsRequest {
}

data class MutationsResponse(
    val lastModificationDate: Long,
    val mutations: List<RemoteModelMutation<PageBookmark>>
)
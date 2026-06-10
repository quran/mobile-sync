package com.quran.shared.syncengine

internal fun SyncMutation.requireSimpleResourceRemoteId(resourceName: String): String {
    val resourceId = this.resourceId
    require(!resourceId.isNullOrBlank()) {
        "Missing resourceId for remote $resourceName mutation"
    }
    return resourceId
}

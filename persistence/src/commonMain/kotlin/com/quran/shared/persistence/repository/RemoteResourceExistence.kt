package com.quran.shared.persistence.repository

import com.quran.shared.persistence.util.SQLITE_MAX_BIND_PARAMETERS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal suspend fun buildRemoteResourceExistenceMap(
    remoteIDs: List<String>,
    fetchExistingRemoteIds: (List<String>) -> Iterable<String>
): Map<String, Boolean> {
    if (remoteIDs.isEmpty()) {
        return emptyMap()
    }

    return withContext(Dispatchers.IO) {
        val existentIDs = remoteIDs
            .chunked(SQLITE_MAX_BIND_PARAMETERS)
            .flatMap(fetchExistingRemoteIds)
            .toSet()
        remoteIDs.associateWith { it in existentIDs }
    }
}

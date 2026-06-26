package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

/**
 * App-facing collection model.
 *
 * The default bookmark collection is virtual: it is identified by [DEFAULT_COLLECTION_ID] and is
 * derived from bookmark membership state instead of a row in the collection table.
 */
data class Collection(
    val name: String,
    val lastUpdated: PlatformDateTime,
    val localId: String
) {
    /**
     * True when this collection represents the virtual default bookmark collection.
     *
     * This value is derived from [localId] so callers do not pass or persist a separate flag.
     */
    val isDefault: Boolean
        get() = localId == DEFAULT_COLLECTION_ID
}

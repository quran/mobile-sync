package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

/**
 * App-facing collection model.
 *
 * @param name the name of the collection
 * @param lastUpdated when the collection was last updated
 * @param id the identifier of the collection
 */
data class Collection(
    val name: String,
    val lastUpdated: PlatformDateTime,
    val id: String
) {
    /**
     * True when this collection represents the virtual default bookmark collection.
     */
    val isDefault: Boolean
        get() = id == DEFAULT_COLLECTION_ID

    /**
     * True when this collection is reserved for ayah highlight persistence.
     */
    val isSystemHighlight: Boolean
        get() = highlightColorForCollectionName(name) != null
}

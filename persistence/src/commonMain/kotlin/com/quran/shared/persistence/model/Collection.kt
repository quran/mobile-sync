package com.quran.shared.persistence.model

import com.quran.shared.persistence.util.PlatformDateTime

/**
 * App-facing collection model.
 *
 * @param name the name of the collection
 * @param lastUpdated when the collection was last updated
 * @param id the identifier of the collection
 * @param isDefault whether this is the default bookmark collection
 * @param isSystem whether this collection is managed by the system
 */
data class Collection(
    val name: String,
    val lastUpdated: PlatformDateTime,
    val id: String,
    val isDefault: Boolean = false,
    val isSystem: Boolean = false
) {
    /**
     * True when this collection is reserved for ayah highlight persistence.
     */
    val isSystemHighlight: Boolean
        get() = highlightColorForCollectionName(name) != null
}

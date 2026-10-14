package com.quran.shared.persistence.model

import com.quran.shared.mutations.trimLikeServer
import com.quran.shared.persistence.util.PlatformDateTime
import kotlin.native.ObjCName

internal const val DEFAULT_COLLECTION_NAME = "Favorites"

/**
 * App-facing collection model.
 *
 * Swift sees it as `BookmarkCollection`: a type named `Collection` would shadow Swift's `Collection` protocol in
 * every file that imports this framework.
 *
 * @param name the name of the collection
 * @param lastUpdated when the collection was last updated
 * @param id the identifier of the collection
 * @param isDefault whether this is the default bookmark collection
 * @param isSystem whether this collection is managed by the system
 * @param isReadOnly whether users can't rename or delete it: system collections, and the color collections older
 *   Quran.com apps created, which the server protects
 */
@ObjCName(swiftName = "BookmarkCollection")
data class Collection(
    val name: String,
    val lastUpdated: PlatformDateTime,
    val id: String,
    val isDefault: Boolean = false,
    val isSystem: Boolean = false,
    val isReadOnly: Boolean = isSystem
) {
    /**
     * True when this collection is reserved for ayah highlight persistence.
     */
    val isSystemHighlight: Boolean
        get() = highlightColorForCollectionName(name) != null
}

/** Names users can't give a collection: Favorites, and the server's reserved `system:` names (highlights among them). */
internal fun isSystemCollectionName(name: String): Boolean {
    val trimmed = name.trimLikeServer()
    return trimmed.equals(DEFAULT_COLLECTION_NAME, ignoreCase = true) ||
        trimmed.lowercase().startsWith(SYSTEM_NAME_PREFIX)
}

private const val SYSTEM_NAME_PREFIX = "system:"

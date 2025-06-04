package com.quran.shared.persistence.model

enum class BookmarkMutationType {
    CREATED,
    DELETED
}

data class BookmarkMutation(
    val page: Int? = null,
    val sura: Int? = null,
    val ayah: Int? = null,
    val remoteId: String? = null,
    val mutationType: BookmarkMutationType,
    val lastUpdated: Long
) {
    init {
        require((page != null && sura == null && ayah == null) || (page == null && sura != null && ayah != null)) {
            "Either page or (sura, ayah) must be set, but not both"
        }
    }
} 
package com.quran.shared.persistence.model

import com.quran.shared.mutations.collectionNameKey

enum class AyahHighlightColor(internal val collectionName: String) {
    BLUE("system:highlights:blue"),
    PINK("system:highlights:pink"),
    GREEN("system:highlights:green"),
    YELLOW("system:highlights:yellow"),
    PURPLE("system:highlights:purple")
}

internal fun highlightColorForCollectionName(name: String): AyahHighlightColor? {
    val normalizedName = collectionNameKey(name)
    return AyahHighlightColor.entries.firstOrNull { it.collectionName == normalizedName }
}

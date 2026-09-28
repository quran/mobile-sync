package com.quran.shared.persistence.model

enum class AyahHighlightColor(internal val collectionName: String) {
    BLUE("system:highlights:blue"),
    PINK("system:highlights:pink"),
    GREEN("system:highlights:green"),
    YELLOW("system:highlights:yellow"),
    PURPLE("system:highlights:purple")
}

internal fun highlightColorForCollectionName(name: String): AyahHighlightColor? {
    val normalizedName = name.trim().lowercase()
    return AyahHighlightColor.entries.firstOrNull { it.collectionName == normalizedName }
}

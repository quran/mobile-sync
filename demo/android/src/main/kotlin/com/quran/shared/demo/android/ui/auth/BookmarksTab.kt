package com.quran.shared.demo.android.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quran.shared.persistence.model.AyahReadingBookmark
import com.quran.shared.persistence.model.EmptyReadingBookmark
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark

@Composable
fun BookmarksTab(
    readingBookmarks: List<ReadingBookmark>,
    onSetReadingAyahBookmark: (Int) -> Unit,
    onSetReadingPageBookmark: (Int) -> Unit,
    onClearReadingBookmark: (Int) -> Unit
) {
    Column {
        Text(
            text = "Your Reading Bookmarks",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        (1..3).forEach { slot ->
            ReadingBookmarkCard(
                slot = slot,
                readingBookmark = readingBookmarks.firstOrNull { it.slot == slot },
                onSetReadingAyahBookmark = { onSetReadingAyahBookmark(slot) },
                onSetReadingPageBookmark = { onSetReadingPageBookmark(slot) },
                onClearReadingBookmark = { onClearReadingBookmark(slot) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ReadingBookmarkCard(
    slot: Int,
    readingBookmark: ReadingBookmark?,
    onSetReadingAyahBookmark: () -> Unit,
    onSetReadingPageBookmark: () -> Unit,
    onClearReadingBookmark: () -> Unit
) {
    val locationText = readingBookmark?.displayText()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Slot $slot${readingBookmark?.name?.let { ": $it" } ?: ""}",
                    style = MaterialTheme.typography.labelLarge
                )
                if (locationText == null) {
                    Text(
                        text = "No reading bookmark set.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = locationText,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            IconButton(onClick = onSetReadingAyahBookmark) {
                Icon(
                    imageVector = Icons.Default.Bookmark,
                    contentDescription = "Set slot $slot to an ayah",
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
            IconButton(onClick = onSetReadingPageBookmark) {
                Icon(
                    imageVector = Icons.Default.Bookmark,
                    contentDescription = "Set slot $slot to a page",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            if (locationText != null) {
                IconButton(onClick = onClearReadingBookmark) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Reading Bookmark",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun ReadingBookmark.displayText(): String? {
    return when (this) {
        is AyahReadingBookmark -> "Surah $sura, Ayah $ayah"
        is PageReadingBookmark -> "Page $page"
        is EmptyReadingBookmark -> null
    }
}

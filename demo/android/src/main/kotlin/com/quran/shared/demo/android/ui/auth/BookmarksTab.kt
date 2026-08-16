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
import com.quran.shared.persistence.model.PageReadingBookmark
import com.quran.shared.persistence.model.ReadingBookmark

@Composable
fun BookmarksTab(
    readingBookmark: ReadingBookmark?,
    onAddReadingAyahBookmark: () -> Unit,
    onAddReadingPageBookmark: () -> Unit,
    onDeleteReadingBookmark: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Your Bookmarks",
                style = MaterialTheme.typography.titleLarge
            )
            
            Row {
                IconButton(onClick = onAddReadingAyahBookmark) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = "Add Reading Ayah Bookmark",
                        tint = MaterialTheme.colorScheme.tertiaryContainer
                    )
                }
                IconButton(onClick = onAddReadingPageBookmark) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = "Add Reading Page Bookmark",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        ReadingBookmarkCard(
            readingBookmark = readingBookmark,
            onDeleteReadingBookmark = onDeleteReadingBookmark
        )
    }
}

@Composable
private fun ReadingBookmarkCard(
    readingBookmark: ReadingBookmark?,
    onDeleteReadingBookmark: () -> Unit
) {
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
                    text = "Current Reading Bookmark",
                    style = MaterialTheme.typography.labelLarge
                )
                if (readingBookmark == null) {
                    Text(
                        text = "No reading bookmark set.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = readingBookmark.displayText(),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            if (readingBookmark != null) {
                IconButton(onClick = onDeleteReadingBookmark) {
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

private fun ReadingBookmark.displayText(): String {
    return when (this) {
        is AyahReadingBookmark -> "Surah $sura, Ayah $ayah"
        is PageReadingBookmark -> "Page $page"
    }
}

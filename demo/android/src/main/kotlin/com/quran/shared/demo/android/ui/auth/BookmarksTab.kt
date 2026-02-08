package com.quran.shared.demo.android.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quran.shared.persistence.model.Bookmark

@Composable
fun BookmarksTab(
    bookmarks: List<Bookmark>,
    onAddPageBookmark: () -> Unit,
    onAddAyahBookmark: () -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit
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
                IconButton(onClick = onAddPageBookmark) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Page Bookmark"
                    )
                }
                IconButton(onClick = onAddAyahBookmark) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Ayah Bookmark",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (bookmarks.isEmpty()) {
            EmptyListMessage("No bookmarks yet.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(bookmarks) { bookmark ->
                    BookmarkItem(
                        bookmark = bookmark,
                        onDelete = { onDeleteBookmark(bookmark) }
                    )
                }
            }
        }
    }
}

@Composable
fun BookmarkItem(
    bookmark: Bookmark,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                when (bookmark) {
                    is Bookmark.PageBookmark -> {
                        Text(
                            text = "Page ${bookmark.page}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    is Bookmark.AyahBookmark -> {
                        Text(
                            text = "Surah ${bookmark.sura}, Ayah ${bookmark.ayah}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Bookmark",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

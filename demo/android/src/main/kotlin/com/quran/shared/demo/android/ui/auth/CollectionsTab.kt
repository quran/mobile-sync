package com.quran.shared.demo.android.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quran.shared.persistence.model.CollectionBookmark
import com.quran.shared.persistence.model.CollectionWithBookmarks

@Composable
fun CollectionsTab(
    collectionsWithBookmarks: List<CollectionWithBookmarks>,
    onAddCollection: (String) -> Unit,
    onDeleteCollection: (String) -> Unit,
    onAddRandomBookmarkToCollection: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showSelectCollectionDialog by remember { mutableStateOf(false) }
    var newCollectionName by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Collection") },
            text = {
                OutlinedTextField(
                    value = newCollectionName,
                    onValueChange = { newCollectionName = it },
                    label = { Text("Collection Name") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newCollectionName.isNotBlank()) {
                        onAddCollection(newCollectionName)
                        newCollectionName = ""
                        showAddDialog = false
                    }
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSelectCollectionDialog) {
        AlertDialog(
            onDismissRequest = { showSelectCollectionDialog = false },
            title = { Text("Select Collection") },
            text = {
                LazyColumn {
                    items(collectionsWithBookmarks) { item ->
                        ListItem(
                            headlineContent = { Text(item.collection.name) },
                            modifier = Modifier.clickable {
                                onAddRandomBookmarkToCollection(item.collection.localId)
                                showSelectCollectionDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSelectCollectionDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Your Collections",
                style = MaterialTheme.typography.titleLarge
            )
            
            Row {
                IconButton(onClick = { showSelectCollectionDialog = true }) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = "Add Random to Collection")
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Collection")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (collectionsWithBookmarks.isEmpty()) {
            EmptyListMessage("No collections yet.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(collectionsWithBookmarks) { collectionWithBookmarks ->
                    CollectionItem(
                        collectionWithBookmarks = collectionWithBookmarks,
                        onDelete = { onDeleteCollection(collectionWithBookmarks.collection.localId) }
                    )
                }
            }
        }
    }
}

@Composable
fun CollectionItem(
    collectionWithBookmarks: CollectionWithBookmarks,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = collectionWithBookmarks.collection.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (collectionWithBookmarks.bookmarks.isEmpty()) {
                    Text(
                        "No bookmarks in this collection",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 40.dp)
                    )
                } else {
                    collectionWithBookmarks.bookmarks.forEach { cb ->
                        Row(
                            modifier = Modifier
                                .padding(start = 40.dp, bottom = 4.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (cb) {
                                    is CollectionBookmark.PageBookmark -> "Page ${cb.page}"
                                    is CollectionBookmark.AyahBookmark -> "Sura ${cb.sura}, Ayah ${cb.ayah}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

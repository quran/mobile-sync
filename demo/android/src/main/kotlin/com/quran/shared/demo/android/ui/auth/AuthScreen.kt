package com.quran.shared.demo.android.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.Collection
import com.quran.shared.persistence.model.CollectionBookmark
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.util.QuranActionsUtils.getRandomAyah
import com.quran.shared.persistence.util.QuranActionsUtils.getRandomPage
import com.quran.shared.persistence.util.QuranActionsUtils.getRandomSura
import com.quran.shared.pipeline.SyncViewModel

/**
 * Authentication screen for the Android demo app.
 */
@Composable
fun AuthScreen(
    viewModel: SyncViewModel,
    onAuthenticationSuccess: () -> Unit = {}
) {
    val authState by viewModel.authState.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState(initial = emptyList())
    val collections by viewModel.collections.collectAsState(initial = emptyList())
    val notes by viewModel.notes.collectAsState(initial = emptyList())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp)
        ) {
            // Title
            Text(
                text = "Quran.com Sync",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Sign in with Quran Foundation",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Content based on auth state
            when (val state = authState) {
                is AuthState.Idle -> {
                    LoginButtonContent(
                        onLoginClick = {
                            viewModel.login()
                        }
                    )
                }
                is AuthState.Loading -> {
                    LoadingContent()
                }
                is AuthState.Success -> {
                    SuccessContent(
                        userInfo = state.userInfo,
                        bookmarks = bookmarks,
                        collections = collections,
                        notes = notes,
                        viewModel = viewModel,
                        onAddPageBookmark = {
                            viewModel.addBookmark(getRandomPage())
                        },
                        onAddAyahBookmark = {
                            val sura = getRandomSura()
                            val ayah = getRandomAyah(sura)
                            viewModel.addBookmark(sura, ayah)
                        },
                        onDeleteBookmark = {
                            viewModel.deleteBookmark(it)
                        },
                        onAddCollection = { name ->
                            viewModel.addCollection(name)
                        },
                        onDeleteCollection = { id ->
                            viewModel.deleteCollection(id)
                        },
                        onAddNote = { body ->
                            viewModel.addNote(body, 1, 1) // Just dummy range for now
                        },
                        onDeleteNote = { id ->
                            viewModel.deleteNote(id)
                        },
                        onLogout = {
                            viewModel.logout()
                        }
                    )
                }
                is AuthState.Error -> {
                    ErrorContent(
                        error = state.message,
                        onRetry = {
                            viewModel.login()
                        },
                        onDismiss = {
                            viewModel.clearError()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginButtonContent(onLoginClick: () -> Unit) {
    Button(
        onClick = onLoginClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            text = "Sign in with OAuth",
            style = MaterialTheme.typography.labelLarge
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "You will be redirected to Quran Foundation to securely sign in.",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LoadingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Signing in...",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SuccessContent(
    userInfo: UserInfo,
    bookmarks: List<Bookmark>,
    collections: List<Collection>,
    notes: List<Note>,
    viewModel: SyncViewModel,
    onAddPageBookmark: () -> Unit,
    onAddAyahBookmark: () -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onAddCollection: (String) -> Unit,
    onDeleteCollection: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onLogout: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Bookmarks", "Collections", "Notes")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // User Info Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Welcome, ${userInfo.displayName ?: "User"}!",
                    style = MaterialTheme.typography.headlineSmall
                )
                
                userInfo.email?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> BookmarksTab(
                    bookmarks = bookmarks,
                    onAddPageBookmark = onAddPageBookmark,
                    onAddAyahBookmark = onAddAyahBookmark,
                    onDeleteBookmark = onDeleteBookmark
                )
                1 -> CollectionsTab(
                    collections = collections,
                    onAddCollection = onAddCollection,
                    onDeleteCollection = onDeleteCollection,
                    viewModel = viewModel
                )
                2 -> NotesTab(
                    notes = notes,
                    onAddNote = onAddNote,
                    onDeleteNote = onDeleteNote
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = onLogout,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Sign Out")
        }
    }
}

@Composable
private fun BookmarksTab(
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
private fun CollectionsTab(
    collections: List<Collection>,
    onAddCollection: (String) -> Unit,
    onDeleteCollection: (String) -> Unit,
    viewModel: SyncViewModel
) {
    var showAddDialog by remember { mutableStateOf(false) }
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
            
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Collection")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (collections.isEmpty()) {
            EmptyListMessage("No collections yet.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(collections) { collection ->
                    CollectionItem(
                        collection = collection,
                        onDelete = { onDeleteCollection(collection.localId) },
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionItem(
    collection: Collection,
    onDelete: () -> Unit,
    viewModel: SyncViewModel
) {
    val bookmarks by viewModel.getBookmarksForCollection(collection.localId).collectAsState(initial = emptyList())
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
                    text = collection.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (bookmarks.isEmpty()) {
                    Text(
                        "No bookmarks in this collection",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 40.dp)
                    )
                } else {
                    bookmarks.forEach { cb ->
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

@Composable
private fun NotesTab(
    notes: List<Note>,
    onAddNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newNoteBody by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Note") },
            text = {
                OutlinedTextField(
                    value = newNoteBody,
                    onValueChange = { newNoteBody = it },
                    label = { Text("Note Content") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newNoteBody.isNotBlank()) {
                        onAddNote(newNoteBody)
                        newNoteBody = ""
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

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Your Notes",
                style = MaterialTheme.typography.titleLarge
            )
            
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Note")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (notes.isEmpty()) {
            EmptyListMessage("No notes yet.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(notes) { note ->
                    NoteItem(
                        note = note,
                        onDelete = { onDeleteNote(note.localId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteItem(
    note: Note,
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
                imageVector = Icons.Default.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.body ?: "",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Ayah ${note.startAyahId} - ${note.endAyahId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Note",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun BookmarkItem(
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

@Composable
private fun EmptyListMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorContent(
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "✗",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Authentication Failed",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            ) {
                Text("Dismiss")
            }

            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f)
            ) {
                Text("Retry")
            }
        }
    }
}

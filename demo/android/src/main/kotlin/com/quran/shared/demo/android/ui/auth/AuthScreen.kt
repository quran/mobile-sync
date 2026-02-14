package com.quran.shared.demo.android.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.persistence.model.Bookmark
import com.quran.shared.persistence.model.CollectionWithBookmarks
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
    val collectionsWithBookmarks by viewModel.collectionsWithBookmarks.collectAsState(initial = emptyList())
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
                        collectionsWithBookmarks = collectionsWithBookmarks,
                        notes = notes,
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
                            val sura = getRandomSura()
                            val ayah = getRandomAyah(sura)
                            viewModel.addNote(
                                body,
                                ayah.toLong(),
                                ayah.toLong()
                            ) // Just dummy range for now
                        },
                        onDeleteNote = { id ->
                            viewModel.deleteNote(id)
                        },
                        onLogout = {
                            viewModel.logout()
                        },
                        onAddRandomBookmarkToCollection = { id ->
                            viewModel.addRandomBookmarkToCollection(id)
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
private fun SuccessContent(
    userInfo: UserInfo,
    bookmarks: List<Bookmark>,
    collectionsWithBookmarks: List<CollectionWithBookmarks>,
    notes: List<Note>,
    onAddPageBookmark: () -> Unit,
    onAddAyahBookmark: () -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onAddCollection: (String) -> Unit,
    onDeleteCollection: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onLogout: () -> Unit,
    onAddRandomBookmarkToCollection: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
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
                    collectionsWithBookmarks = collectionsWithBookmarks,
                    onAddCollection = onAddCollection,
                    onDeleteCollection = onDeleteCollection,
                    onAddRandomBookmarkToCollection = onAddRandomBookmarkToCollection
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

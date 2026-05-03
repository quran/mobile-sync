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
import com.quran.shared.persistence.model.AyahBookmark
import com.quran.shared.persistence.model.CollectionWithAyahBookmarks
import com.quran.shared.persistence.model.Note
import com.quran.shared.persistence.model.ReadingBookmark
import com.quran.shared.persistence.model.ReadingSession
import com.quran.shared.demo.common.util.QuranActionsUtils.getRandomAyah
import com.quran.shared.demo.common.util.QuranActionsUtils.getRandomSura
import com.quran.shared.demo.android.ui.SyncViewModel
import kotlinx.coroutines.launch

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
    val readingBookmark by viewModel.readingBookmark.collectAsState(initial = null)
    val collectionsWithBookmarks by viewModel.collectionsWithBookmarks.collectAsState(initial = emptyList())
    val notes by viewModel.notes.collectAsState(initial = emptyList())
    val readingSessions by viewModel.readingSessions.collectAsState(initial = emptyList())

    val scope = rememberCoroutineScope()
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
                            scope.launch {
                                try {
                                    viewModel.login()
                                } catch (e: Exception) {
                                }
                            }
                        },
                        onReauthenticateLoginClick = {
                            scope.launch {
                                try {
                                    viewModel.loginWithReauthentication()
                                } catch (e: Exception) {
                                }
                            }
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
                        readingBookmark = readingBookmark,
                        collectionsWithBookmarks = collectionsWithBookmarks,
                        notes = notes,
                        onAddAyahBookmark = {
                            val sura = getRandomSura()
                            val ayah = getRandomAyah(sura)
                            scope.launch {
                                try {
                                    viewModel.addBookmark(sura, ayah)
                                } catch (e: Exception) {
                                }
                            }
                        },
                        onAddReadingAyahBookmark = {
                            val sura = getRandomSura()
                            val ayah = getRandomAyah(sura)
                            scope.launch {
                                try {
                                    viewModel.addReadingBookmark(sura, ayah)
                                } catch (e: Exception) {
                                }
                            }
                        },
                        onDeleteBookmark = {
                            scope.launch {
                                try {
                                    viewModel.deleteBookmark(it)
                                } catch (e: Exception) {
                                }
                            }
                        },
                        onDeleteReadingBookmark = {
                            scope.launch {
                                try {
                                    viewModel.deleteReadingBookmark()
                                } catch (e: Exception) {
                                }
                            }
                        },
                        onAddCollection = { name ->
                            scope.launch {
                                try {
                                    viewModel.addCollection(name)
                                } catch (e: Exception) {
                                }
                            }
                        },
                        onDeleteCollection = { id ->
                            scope.launch {
                                try {
                                    viewModel.deleteCollection(id)
                                } catch (e: Exception) {
                                }
                            }
                        },
                        onAddNote = { body ->
                            val sura = getRandomSura()
                            val ayah = getRandomAyah(sura)
                            scope.launch {
                                try {
                                    viewModel.addNote(
                                        body,
                                        ayah.toLong(),
                                        ayah.toLong()
                                    )
                                } catch (e: Exception) {
                                }
                            } // Just dummy range for now
                        },
                        onDeleteNote = { id ->
                            scope.launch {
                                try {
                                    viewModel.deleteNote(id)
                                } catch (e: Exception) {
                                }
                            }
                        },
                        onLogout = { clearLocalData ->
                            scope.launch {
                                try {
                                    viewModel.logout(clearLocalData)
                                } catch (e: Exception) {
                                }
                            }
                        },
                        onAddRandomBookmarkToCollection = { id ->
                            scope.launch {
                                try {
                                    val sura = getRandomSura()
                                    val ayah = getRandomAyah(sura)
                                    viewModel.addAyahBookmarkToCollection(id, sura, ayah)
                                } catch (e: Exception) {
                                }
                            }
                        },
                        readingSessions = readingSessions,
                        onAddReadingSession = {
                            val sura = getRandomSura()
                            val ayah = getRandomAyah(sura)
                            scope.launch {
                                try {
                                    viewModel.addReadingSession(sura, ayah)
                                } catch (e: Exception) {
                                }
                            }
                        }
                    )
                }

                is AuthState.Error -> {
                    ErrorContent(
                        error = state.message,
                        onRetry = {
                            scope.launch {
                                try {
                                    viewModel.login()
                                } catch (e: Exception) {
                                }
                            }
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
    bookmarks: List<AyahBookmark>,
    readingBookmark: ReadingBookmark?,
    collectionsWithBookmarks: List<CollectionWithAyahBookmarks>,
    notes: List<Note>,
    onAddAyahBookmark: () -> Unit,
    onAddReadingAyahBookmark: () -> Unit,
    onDeleteBookmark: (AyahBookmark) -> Unit,
    onDeleteReadingBookmark: () -> Unit,
    onAddCollection: (String) -> Unit,
    onDeleteCollection: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onLogout: (Boolean) -> Unit,
    onAddRandomBookmarkToCollection: (String) -> Unit,
    readingSessions: List<ReadingSession>,
    onAddReadingSession: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Bookmarks", "Collections", "Notes", "Reading")

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
                    readingBookmark = readingBookmark,
                    onAddAyahBookmark = onAddAyahBookmark,
                    onAddReadingAyahBookmark = onAddReadingAyahBookmark,
                    onDeleteReadingBookmark = onDeleteReadingBookmark,
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

                3 -> ReadingSessionsTab(
                    readingSessions = readingSessions,
                    onAddReadingSession = onAddReadingSession
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        var clearLocalData by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = clearLocalData, onCheckedChange = { clearLocalData = it })
            Text("Clear local data on sign out", style = MaterialTheme.typography.bodySmall)
        }

        TextButton(
            onClick = { onLogout(clearLocalData) },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Sign Out")
        }
    }
}

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
import com.quran.shared.demo.common.util.QuranActionsUtils.getRandomPage
import com.quran.shared.demo.common.util.QuranActionsUtils.getRandomSura
import com.quran.shared.demo.android.ui.SyncViewModel
import kotlinx.coroutines.CoroutineScope
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
    val authenticationConfigured = viewModel.isAuthenticationConfigured

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
                text = if (authenticationConfigured) {
                    "Sign in with Quran Foundation"
                } else {
                    "Local demo storage"
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Content based on auth state
            when (val state = authState) {
                is AuthState.Idle -> {
                    if (authenticationConfigured) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
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

                            Spacer(modifier = Modifier.height(24.dp))

                            LocalDataContent(
                                eventScope = scope,
                                viewModel = viewModel,
                                userInfo = null,
                                signedIn = false,
                                statusMessage = "Signed out. Local changes are available before sign-in.",
                                bookmarks = bookmarks,
                                readingBookmark = readingBookmark,
                                collectionsWithBookmarks = collectionsWithBookmarks,
                                notes = notes,
                                readingSessions = readingSessions
                            )
                        }
                    } else {
                        LocalDataContent(
                            eventScope = scope,
                            viewModel = viewModel,
                            userInfo = null,
                            signedIn = false,
                            statusMessage = "OAuth credentials are not configured for this build.",
                            bookmarks = bookmarks,
                            readingBookmark = readingBookmark,
                            collectionsWithBookmarks = collectionsWithBookmarks,
                            notes = notes,
                            readingSessions = readingSessions
                        )
                    }
                }

                is AuthState.Loading -> {
                    LoadingContent()
                }

                is AuthState.Success -> {
                    LocalDataContent(
                        eventScope = scope,
                        viewModel = viewModel,
                        userInfo = state.userInfo,
                        signedIn = true,
                        statusMessage = null,
                        bookmarks = bookmarks,
                        readingBookmark = readingBookmark,
                        collectionsWithBookmarks = collectionsWithBookmarks,
                        notes = notes,
                        readingSessions = readingSessions
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
private fun LocalDataContent(
    eventScope: CoroutineScope,
    viewModel: SyncViewModel,
    userInfo: UserInfo?,
    signedIn: Boolean,
    statusMessage: String?,
    bookmarks: List<AyahBookmark>,
    readingBookmark: ReadingBookmark?,
    collectionsWithBookmarks: List<CollectionWithAyahBookmarks>,
    notes: List<Note>,
    readingSessions: List<ReadingSession>
) {
    DataContent(
        userInfo = userInfo,
        signedIn = signedIn,
        statusMessage = statusMessage,
        bookmarks = bookmarks,
        readingBookmark = readingBookmark,
        collectionsWithBookmarks = collectionsWithBookmarks,
        notes = notes,
        onAddAyahBookmark = {
            val sura = getRandomSura()
            val ayah = getRandomAyah(sura)
            eventScope.launch {
                try {
                    viewModel.addBookmark(sura, ayah)
                } catch (e: Exception) {
                }
            }
        },
        onAddReadingAyahBookmark = {
            val sura = getRandomSura()
            val ayah = getRandomAyah(sura)
            eventScope.launch {
                try {
                    viewModel.addAyahReadingBookmark(sura, ayah)
                } catch (e: Exception) {
                }
            }
        },
        onAddReadingPageBookmark = {
            val page = getRandomPage()
            eventScope.launch {
                try {
                    viewModel.addPageReadingBookmark(page)
                } catch (e: Exception) {
                }
            }
        },
        onDeleteBookmark = {
            eventScope.launch {
                try {
                    viewModel.deleteBookmark(it)
                } catch (e: Exception) {
                }
            }
        },
        onDeleteReadingBookmark = {
            eventScope.launch {
                try {
                    viewModel.deleteReadingBookmark()
                } catch (e: Exception) {
                }
            }
        },
        onAddCollection = { name ->
            eventScope.launch {
                try {
                    viewModel.addCollection(name)
                } catch (e: Exception) {
                }
            }
        },
        onDeleteCollection = { id ->
            eventScope.launch {
                try {
                    viewModel.deleteCollection(id)
                } catch (e: Exception) {
                }
            }
        },
        onAddNote = { body ->
            val sura = getRandomSura()
            val ayah = getRandomAyah(sura)
            eventScope.launch {
                try {
                    viewModel.addNote(
                        body,
                        sura,
                        ayah,
                        sura,
                        ayah
                    )
                } catch (e: Exception) {
                }
            }
        },
        onDeleteNote = { id ->
            eventScope.launch {
                try {
                    viewModel.deleteNote(id)
                } catch (e: Exception) {
                }
            }
        },
        onLogout = {
            eventScope.launch {
                try {
                    viewModel.logout()
                } catch (e: Exception) {
                }
            }
        },
        onAddRandomBookmarkToCollection = { id ->
            eventScope.launch {
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
            eventScope.launch {
                try {
                    viewModel.addReadingSession(sura, ayah)
                } catch (e: Exception) {
                }
            }
        }
    )
}

@Composable
private fun DataContent(
    userInfo: UserInfo?,
    signedIn: Boolean,
    statusMessage: String?,
    bookmarks: List<AyahBookmark>,
    readingBookmark: ReadingBookmark?,
    collectionsWithBookmarks: List<CollectionWithAyahBookmarks>,
    notes: List<Note>,
    onAddAyahBookmark: () -> Unit,
    onAddReadingAyahBookmark: () -> Unit,
    onAddReadingPageBookmark: () -> Unit,
    onDeleteBookmark: (AyahBookmark) -> Unit,
    onDeleteReadingBookmark: () -> Unit,
    onAddCollection: (String) -> Unit,
    onDeleteCollection: (String) -> Unit,
    onAddNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onLogout: () -> Unit,
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
                    text = if (signedIn) {
                        "Welcome, ${userInfo?.displayName ?: "User"}!"
                    } else {
                        "Local data mode"
                    },
                    style = MaterialTheme.typography.headlineSmall
                )

                if (!signedIn && statusMessage != null) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }

                userInfo?.email?.let {
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
                    onAddReadingPageBookmark = onAddReadingPageBookmark,
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

        if (signedIn) {
            TextButton(
                onClick = onLogout,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Sign Out")
            }
        }
    }
}

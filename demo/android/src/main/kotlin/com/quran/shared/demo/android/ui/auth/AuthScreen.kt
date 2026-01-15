package com.quran.shared.demo.android.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Authentication screen for the Android demo app.
 *
 * Displays:
 * - Login button to initiate OAuth flow
 * - Loading state during authentication
 * - Success message after successful login
 * - Error messages for failed authentication
 *
 * Follows Jetpack Compose best practices with state management via ViewModel.
 */
@Composable
fun AuthScreen(
    viewModel: AuthViewModel = viewModel(),
    onAuthenticationSuccess: () -> Unit = {}
) {
    val authState by viewModel.authState.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(authState) {
        if (authState is AuthState.Success) {
            onAuthenticationSuccess()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
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
            when (authState) {
                is AuthState.Idle -> {
                    LoginButtonContent(
                        onLoginClick = {
                            viewModel.login(context as android.app.Activity)
                        }
                    )
                }
                is AuthState.Loading -> {
                    LoadingContent()
                }
                is AuthState.Success -> {
                    SuccessContent()
                }
                is AuthState.Error -> {
                    ErrorContent(
                        error = error,
                        onRetry = {
                            viewModel.login(context as android.app.Activity)
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
private fun SuccessContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "✓",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Successfully signed in!",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Your session is now active.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
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


package com.quran.shared.demo.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.quran.shared.demo.android.ui.auth.AuthScreen
import com.quran.shared.demo.android.ui.auth.AuthViewModel
import kotlinx.coroutines.launch

/**
 * Main activity for the Android demo app.
 *
 * Responsibilities:
 * - Display the authentication screen
 * - Handle OAuth redirect callbacks from the system browser
 * - Manage activity lifecycle and deep linking
 *
 * When user completes OAuth flow in browser, the app is opened via deep link
 * with a redirect URI containing the authorization code. This activity captures
 * that redirect and passes it to the AuthViewModel for token exchange.
 */
class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by lazy { AuthViewModel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AuthScreen(
                viewModel = authViewModel,
                onAuthenticationSuccess = {
                    // Navigate to main app or home screen
                    // For now, just log success
                    println("Authentication successful!")
                }
            )
        }

        // Handle OAuth redirect if this activity was opened via deep link
        handleOAuthRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle OAuth redirect when app is already running
        handleOAuthRedirect(intent)
    }

    /**
     * Extracts OAuth redirect URI from intent and notifies ViewModel.
     *
     * The AndroidManifest.xml is configured to handle intents with the scheme
     * "com.quran.oauth" and host "callback", which matches the redirectUri in
     * AuthenticationManager.
     *
     * Example redirect URI: com.quran.oauth://callback?code=AUTH_CODE&state=STATE
     */
    private fun handleOAuthRedirect(intent: Intent) {
        val action = intent.action
        val uri = intent.data

        if (action == Intent.ACTION_VIEW && uri != null) {
            val redirectUri = uri.toString()

            lifecycleScope.launch {
                authViewModel.handleOAuthRedirect(redirectUri)
            }
        }
    }
}
package com.quran.shared.demo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.quran.shared.demo.android.ui.auth.AuthScreen
import com.quran.shared.auth.ui.AuthViewModel
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import org.publicvalue.multiplatform.oidc.appsupport.AndroidCodeAuthFlowFactory

/**
 * Main activity for the Android demo app.
 *
 * Key responsibilities:
 * - Initialize the AndroidCodeAuthFlowFactory for OAuth browser flow
 * - Register the factory with AuthFlowFactoryProvider for use by the auth module
 * - Display the authentication screen
 *
 * The OIDC library handles all browser launching and redirect handling internally.
 */
class MainActivity : ComponentActivity() {
    
    // Single instance of the factory - persists across activity recreations
    private val codeAuthFlowFactory = AndroidCodeAuthFlowFactory(useWebView = false)
    
    private val authViewModel: AuthViewModel by lazy { AuthViewModel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Register this activity with the factory for browser auth
        codeAuthFlowFactory.registerActivity(this)
        
        // Initialize the global factory provider so the auth module can access it
        AuthFlowFactoryProvider.initialize(codeAuthFlowFactory)

        setContent {
            AuthScreen(
                viewModel = authViewModel,
                onAuthenticationSuccess = {
                    println("Authentication successful!")
                }
            )
        }
    }
}
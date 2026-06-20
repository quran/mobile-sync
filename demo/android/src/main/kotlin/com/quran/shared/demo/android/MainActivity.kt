package com.quran.shared.demo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.demo.android.ui.auth.AuthScreen
import com.quran.shared.persistence.DriverFactory
import com.quran.shared.pipeline.AppEnvironment
import com.quran.shared.pipeline.di.SharedDependencyGraph
import com.quran.shared.pipeline.storage.createMobileSyncStorage
import com.quran.shared.demo.android.ui.SyncViewModel
import org.publicvalue.multiplatform.oidc.appsupport.AndroidCodeAuthFlowFactory

/**
 * Main activity for the Android demo app.
 *
 * Key responsibilities:
 * - Build the managed sync graph without requiring an Activity
 * - Register the Activity-backed OAuth browser flow before interactive login
 * - Display the authentication screen
 * - Initialize and Start Sync Engine
 *
 * The auth module contributes the exported OAuth redirect proxy. The upstream OIDC activity is
 * kept internal so only in-app browser launch requests can reach it.
 */
class MainActivity : ComponentActivity() {
    
    // Single instance of the factory - persists across activity recreations
    private val codeAuthFlowFactory = AndroidCodeAuthFlowFactory(useWebView = false)

    private val mainViewModel: SyncViewModel by lazy {
        val driverFactory = DriverFactory(context = this.applicationContext)
        val graph = SharedDependencyGraph.init(
            driverFactory = driverFactory,
            storage = createMobileSyncStorage(this.applicationContext),
            appEnvironment = AppEnvironment.PRELIVE,
            clientId = BuildConfig.OAUTH_CLIENT_ID
        )
        
        SyncViewModel(graph.authService, graph.syncService)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel = mainViewModel

        if (viewModel.isAuthenticationConfigured) {
            // Graph construction above is Activity-free. The Activity-backed factory is only
            // required for interactive browser login and redirect handling.
            codeAuthFlowFactory.registerActivity(this)
            AuthFlowFactoryProvider.initialize(codeAuthFlowFactory)
        }

        setContent {
            AuthScreen(
                viewModel = viewModel,
                onAuthenticationSuccess = {
                    println("Authentication successful!")
                    viewModel.triggerSync()
                }
            )
        }
    }
}

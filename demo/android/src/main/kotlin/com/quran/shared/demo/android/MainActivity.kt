package com.quran.shared.demo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.quran.shared.auth.di.AuthConfigFactory
import com.quran.shared.auth.di.AuthFlowFactoryProvider
import com.quran.shared.demo.android.ui.auth.AuthScreen
import com.quran.shared.persistence.DriverFactory
import com.quran.shared.persistence.makeDatabase
import com.quran.shared.persistence.repository.bookmark.repository.BookmarksRepositoryImpl
import com.quran.shared.persistence.repository.collection.repository.CollectionsRepositoryImpl
import com.quran.shared.persistence.repository.collectionbookmark.repository.CollectionBookmarksRepositoryImpl
import com.quran.shared.pipeline.SyncEnginePipeline
import com.quran.shared.pipeline.SyncService
import com.quran.shared.pipeline.SyncViewModel
import com.quran.shared.syncengine.SynchronizationEnvironment
import org.publicvalue.multiplatform.oidc.appsupport.AndroidCodeAuthFlowFactory

/**
 * Main activity for the Android demo app.
 *
 * Key responsibilities:
 * - Initialize the AndroidCodeAuthFlowFactory for OAuth browser flow
 * - Register the factory with AuthFlowFactoryProvider for use by the auth module
 * - Display the authentication screen
 * - Initialize and Start Sync Engine
 *
 * The OIDC library handles all browser launching and redirect handling internally.
 */
class MainActivity : ComponentActivity() {
    
    // Single instance of the factory - persists across activity recreations
    private val codeAuthFlowFactory = AndroidCodeAuthFlowFactory(useWebView = false)
    
    private val mainViewModel: SyncViewModel by lazy {
        val authService = AuthConfigFactory.authService
        val driverFactory = DriverFactory(context = this)
        val database = makeDatabase(driverFactory)
        
        val pipeline = SyncEnginePipeline(
            bookmarksRepository = BookmarksRepositoryImpl(database),
            collectionsRepository = CollectionsRepositoryImpl(database),
            collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(database)
        )
        
        val service = SyncService(
            authService = authService,
            pipeline = pipeline,
            environment = SynchronizationEnvironment(endPointURL = "https://apis-prelive.quran.foundation/auth") // todo configure url env
        )

        SyncViewModel(
            authService = authService,
            service = service
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Register this activity with the factory for browser auth
        codeAuthFlowFactory.registerActivity(this)
        
        // Initialize the global factory provider so the auth module can access it
        AuthFlowFactoryProvider.initialize(codeAuthFlowFactory)

        setContent {
            AuthScreen(
                viewModel = mainViewModel,
                onAuthenticationSuccess = {
                    println("Authentication successful!")
                    mainViewModel.triggerSync()
                }
            )
        }
    }
}
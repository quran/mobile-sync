import SwiftUI
import UIKit
import Shared


/**
 * Main App entry point for the iOS demo.
 *
 * Sets up the scene and authentication flow.
 */
@main
struct QuranSyncDemoApp: App {

    init() {
        // Initialize the OIDC factory for iOS
        Shared.AuthFlowFactoryProvider.shared.doInitialize()

        // Initialize Metro DI Graph
        let driverFactory = DriverFactory()
        let environment = SynchronizationEnvironment(endPointURL: "https://apis-prelive.quran.foundation/auth")
        _ = SharedDependencyGraph.shared.doInit(driverFactory: driverFactory, environment: environment)
    }

    @StateObject private var viewModel: SyncViewModel = {
        let graph = SharedDependencyGraph.shared.get()
        return SyncViewModel(authService: graph.authService, syncService: graph.syncService)
    }()

    @State private var isAuthenticated = false

    var body: some Scene {
        WindowGroup {
            VStack {
                if isAuthenticated {
                    Text("Ready to Sync!")
                        .font(.headline)
                        .foregroundColor(.white)
                        .padding()
                        .background(Color.green.opacity(0.8))
                        .cornerRadius(12)
                        .padding()
                }
                AuthView(viewModel: viewModel, onAuthenticationSuccess: {
                    isAuthenticated = true
                })
            }
        }
    }
}



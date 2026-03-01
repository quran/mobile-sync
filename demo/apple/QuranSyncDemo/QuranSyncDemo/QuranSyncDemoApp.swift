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

    @StateObject private var viewModel: SyncViewModel
    @State private var isAuthenticated = false

    init() {
        let graph = IOSDependencyGraph.shared.get()
        _viewModel = StateObject(
            wrappedValue: SyncViewModel(authService: graph.authService, syncService: graph.syncService)
        )
    }

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


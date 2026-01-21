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
    }
    
    // Use the native ViewModel
    @StateObject private var viewModel = AuthViewModel()
    
    @State private var isAuthenticated = false

    var body: some Scene {
        WindowGroup {
            VStack {
                // Show authentication success
                if isAuthenticated {
                    Text("Ready to Sync!")
                        .font(.headline)
                        .foregroundColor(.white)
                    .padding()
                    .background(Color.green.opacity(0.8))
                    .cornerRadius(12)
                    .padding()
                }
                // Main auth screen
                AuthView(viewModel: viewModel, onAuthenticationSuccess: {
                    isAuthenticated = true
                })
            }
        }
    }
}

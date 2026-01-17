import SwiftUI
import UIKit
import Shared

/**
 * App delegate.
 *
 * Kept for potential future use (e.g. push notifications setup), but URL handling
 * is now managed by the pure SwiftUI .onOpenURL modifier.
 */
class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        return UISceneConfiguration(
            name: nil,
            sessionRole: connectingSceneSession.role
        )
    }
}

/**
 * Main App entry point for the iOS demo.
 *
 * Sets up the scene, authentication flow, and handles deep link redirects via .onOpenURL.
 */
@main
struct QuranSyncDemoApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    
    // Hoist the ViewModel to handle deep links at app level
    @StateObject private var viewModel = ObservableViewModel(Shared.AuthViewModel()) { vm, object in
        [
            vm.authState.watch { _ in object.objectWillChange.send() },
            vm.error.watch { _ in object.objectWillChange.send() }
        ]
    }
    
    @State private var isAuthenticating = false

    var body: some Scene {
        WindowGroup {
            ZStack {
                // Main auth screen
                AuthView(viewModel: viewModel, onAuthenticationSuccess: {
                    isAuthenticating = true
                })

                // Overlay for showing authentication success
                if isAuthenticating {
                    Color.black.opacity(0.4)
                        .ignoresSafeArea()

                    VStack {
                        Text("Ready to Sync!")
                            .font(.headline)
                            .foregroundColor(.white)
                    }
                    .padding()
                    .background(Color.green.opacity(0.8))
                    .cornerRadius(12)
                    .padding()
                }
            }
            // Replace NotificationCenter with native SwiftUI URL handling
            .onOpenURL { url in
                if url.scheme == "com.quran.oauth" && url.host == "callback" {
                    viewModel.kt.handleOAuthRedirect(redirectUri: url.absoluteString)
                }
            }
        }
    }
}

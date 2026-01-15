import SwiftUI
import UIKit

/**
 * App delegate that handles OAuth deep link redirects on iOS.
 *
 * When the user completes OAuth flow in the browser, the system opens the app
 * via a deep link with the authorization code. This delegate captures that
 * redirect and notifies the AuthViewModel.
 */
class AppDelegate: NSObject, UIApplicationDelegate {

    /**
     * Called when the app is opened with a URL (deep link).
     *
     * This handles OAuth redirects from the system browser.
     * The redirect URL format: quran-sync://oauth/callback?code=AUTH_CODE&state=STATE
     */
    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        // Handle OAuth redirect
        if url.scheme == "com.quran.oauth" && url.host == "callback" {
            // Notify the app about the OAuth callback
            NotificationCenter.default.post(
                name: NSNotification.Name("OAuthRedirect"),
                object: nil,
                userInfo: ["url": url]
            )
            return true
        }

        return false
    }

    /**
     * Called when the app is opened via scene activation (iOS 13+).
     */
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(
            name: nil,
            sessionRole: connectingSceneSession.role
        )

        // Handle URL context from scene options
        if let urlContext = options.urlContexts.first {
            NotificationCenter.default.post(
                name: NSNotification.Name("OAuthRedirect"),
                object: nil,
                userInfo: ["url": urlContext.url]
            )
        }

        return configuration
    }
}

/**
 * Main App entry point for the iOS demo.
 *
 * Sets up the scene, authentication flow, and handles deep link redirects.
 */
@main
struct QuranSyncDemoApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var authViewModel = AuthViewModel()
    @State private var isAuthenticating = false

    var body: some Scene {
        WindowGroup {
            ZStack {
                // Main auth screen
                AuthView(onAuthenticationSuccess: {
                    isAuthenticating = true
                })
                .environmentObject(authViewModel)

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
            .onReceive(
                NotificationCenter.default.publisher(
                    for: NSNotification.Name("OAuthRedirect")
                )
            ) { notification in
                if let url = notification.userInfo?["url"] as? URL {
                    authViewModel.handleOAuthRedirect(url: url)
                }
            }
        }
    }
}


import SwiftUI
import AuthenticationServices
import Shared

/**
 * Authentication screen for iOS demo app.
 *
 * Displays:
 * - Login button to initiate OAuth flow
 * - Loading state during authentication
 * - Success message after successful login
 * - Error messages for failed authentication
 *
 * Uses SwiftUI with MVVM pattern for state management.
 */
struct AuthView: View {
    @ObservedObject var viewModel: ObservableViewModel<Shared.AuthViewModel>
    
    @State private var webAuthSession: ASWebAuthenticationSession?
    var onAuthenticationSuccess: () -> Void = {}

    var body: some View {
        ZStack {
            // Background
            Color(.systemBackground)
            .ignoresSafeArea()

            VStack(spacing: 24) {
                // Header
                VStack(spacing: 8) {
                    Text("Quran.com Sync")
                        .font(.largeTitle)
                        .fontWeight(.bold)

                    Text("Sign in with Quran Foundation")
                        .font(.body)
                        .foregroundColor(.secondary)
                }
                .padding(.bottom, 32)
                
                // Pure Kotlin ViewModel Access
                // Content based on auth state
                Group {
                    if let state = viewModel.kt.authState.value as? Shared.AuthState {
                        if state is Shared.AuthState.Idle {
                            loginButtonContent
                        } else if state is Shared.AuthState.Loading {
                            loadingContent
                        } else if state is Shared.AuthState.Success {
                            successContent
                        } else if state is Shared.AuthState.Error {
                            errorContent
                        } else if let startAuth = state as? Shared.AuthState.StartAuthFlow {
                            loadingContent
                            .onAppear {
                                launchAuthSession(url: startAuth.authUrl)
                            }
                        }
                    }
                }

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 32)
        }
        // Observe Kotlin StateFlow changes via the wrapper
        .onChange(of: viewModel.kt.authState.value as? Shared.AuthState) { _, newState in
            if newState is Shared.AuthState.Success {
                onAuthenticationSuccess()
            }
        }
    }
    
    private func launchAuthSession(url: String) {
        guard let authUrl = URL(string: url) else { return }

        webAuthSession = ASWebAuthenticationSession(
            url: authUrl,
            callbackURLScheme: "com.quran.oauth"
        ) { callbackUrl, error in
            if let error = error {
                 // Handle cancellation
                 if let authError = error as? ASWebAuthenticationSessionError, authError.code == .canceledLogin {
                     // Reset state logic if needed, or rely on ViewModel
                 }
                 return
            }

            if let callbackUrl = callbackUrl {
                viewModel.kt.handleOAuthRedirect(redirectUri: callbackUrl.absoluteString)
            }
        }
        
        // Context provider usually needed, but starting iOS 13+ can sometimes infer.
        // For strict correctness we need a provider, but let's try standard start() which covers most cases
        // or minimal context provider.
        webAuthSession?.presentationContextProvider = ContextProvider.shared
        webAuthSession?.prefersEphemeralWebBrowserSession = false
        webAuthSession?.start()
    }
    
    // Helper Class for ASWebAuthenticationPresentationContextProviding
    class ContextProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
        static let shared = ContextProvider()
        func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
            return ASPresentationAnchor()
        }
    }

    // MARK: - Content Views

    private var loginButtonContent: some View {
        VStack(spacing: 16) {
            Button(action: { viewModel.kt.login() }) {
                Text("Sign in with OAuth")
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Color.accentColor)
                    .cornerRadius(8)
            }

            Text("You will be redirected to Quran Foundation to securely sign in.")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private var loadingContent: some View {
        VStack(spacing: 16) {
            ProgressView()
                .scaleEffect(1.5, anchor: .center)
                .padding()

            Text("Signing in...")
                .font(.body)
        }
    }

    private var successContent: some View {
        VStack(spacing: 16) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 60))
                .foregroundColor(.green)

            Text("Successfully signed in!")
                .font(.headline)

            Text("Your session is now active.")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding()
    }

    private var errorContent: some View {
        VStack(spacing: 16) {
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 60))
                .foregroundColor(.red)

            Text("Authentication Failed")
                .font(.headline)

            if let error = viewModel.kt.error.value {
                Text(error as String)
                    .font(.caption)
                    .foregroundColor(.red)
                    .padding(12)
                    .background(Color(.systemRed).opacity(0.1))
                    .cornerRadius(8)
                    .multilineTextAlignment(.center)
            }

            HStack(spacing: 12) {
                Button("Dismiss") {
                    viewModel.kt.clearError()
                }
                .buttonStyle(.bordered)

                Button("Retry") {
                    viewModel.kt.login()
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding()
    }
}

// MARK: - Preview
#Preview {
    AuthView(viewModel: ObservableViewModel(Shared.AuthViewModel()) { _, _ in [] })
}


import SwiftUI
import AuthenticationServices

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
    @StateObject private var viewModel = AuthViewModel()
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

                // Content based on auth state
                Group {
                    switch viewModel.authState {
                    case .idle:
                        loginButtonContent
                    case .loading:
                        loadingContent
                    case .success:
                        successContent
                    case .error:
                        errorContent
                    }
                }

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 32)
        }
        .onChange(of: viewModel.authState) { _, newState in
            if case .success = newState {
                onAuthenticationSuccess()
            }
        }
    }

    // MARK: - Content Views

    private var loginButtonContent: some View {
        VStack(spacing: 16) {
            Button(action: { viewModel.login() }) {
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

            if let error = viewModel.error {
                Text(error)
                    .font(.caption)
                    .foregroundColor(.red)
                    .padding(12)
                    .background(Color(.systemRed).opacity(0.1))
                    .cornerRadius(8)
                    .multilineTextAlignment(.center)
            }

            HStack(spacing: 12) {
                Button("Dismiss") {
                    viewModel.clearError()
                }
                .buttonStyle(.bordered)

                Button("Retry") {
                    viewModel.login()
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding()
    }
}

// MARK: - Preview
#Preview {
    AuthView()
}


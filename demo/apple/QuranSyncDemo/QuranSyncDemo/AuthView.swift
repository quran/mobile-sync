import SwiftUI
import Shared

/**
 * Authentication screen for iOS demo app.
 *
 * Displays:
 * - Login button to initiate OAuth flow via OIDC library
 * - Loading state during authentication
 * - Success message after successful login with user info and bookmarks
 * - Error messages for failed authentication
 *
 * This version uses the shared OIDC logic, which handles browser launching
 * and redirect handling internally via ASWebAuthenticationSession.
 */
struct AuthView: View {
    @ObservedObject var viewModel: ObservableViewModel<Shared.AuthViewModel>
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
                    if let state = viewModel.kt.authState.value {
                        if state is Shared.AuthState.Idle {
                            loginButtonContent
                        } else if state is Shared.AuthState.Loading {
                            loadingContent
                        } else if let successState = state as? Shared.AuthState.Success {
                            successContent(userInfo: successState.userInfo)
                        } else if state is Shared.AuthState.Error {
                            errorContent
                        }
                    }
                }

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 32)
        }
        .onChange(of: viewModel.kt.authState.value) { _, newState in
            if newState is Shared.AuthState.Success {
                onAuthenticationSuccess()
            }
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

    @State private var bookmarks: [Shared.Bookmark.PageBookmark] = []
    
    private let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()

    private func successContent(userInfo: Shared.UserInfo) -> some View {
        ScrollView {
            VStack(spacing: 24) {
                // User Profile
                VStack(spacing: 16) {
                    if let photoUrl = userInfo.photoUrl, let url = URL(string: photoUrl) {
                        AsyncImage(url: url) { image in
                            image.resizable()
                        } placeholder: {
                            Image(systemName: "person.circle.fill")
                                .resizable()
                                .foregroundColor(.gray)
                        }
                        .frame(width: 80, height: 80)
                        .clipShape(Circle())
                    } else {
                        Image(systemName: "person.circle.fill")
                            .resizable()
                            .foregroundColor(.gray)
                            .frame(width: 80, height: 80)
                    }

                    VStack(spacing: 4) {
                        Text("Welcome, \(userInfo.name ?? "User")!")
                            .font(.title2)
                            .fontWeight(.bold)

                        if let email = userInfo.email {
                            Text(email)
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding()
                .frame(maxWidth: .infinity)
                .background(Color(.secondarySystemBackground))
                .cornerRadius(12)

                // Bookmarks Section
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Text("Your Bookmarks")
                            .font(.headline)
                        
                        Spacer()
                        
                        Button(action: {
                            Task {
                                try? await DatabaseManager.shared.addRandomBookmark()
                            }
                        }) {
                            Image(systemName: "plus.circle.fill")
                                .font(.title2)
                        }
                    }

                    if bookmarks.isEmpty {
                        Text("No bookmarks yet.")
                            .foregroundColor(.secondary)
                            .italic()
                            .frame(maxWidth: .infinity, alignment: .center)
                            .padding()
                    } else {
                        ForEach(bookmarks, id: \.self) { bookmark in
                            HStack {
                                Image(systemName: "bookmark.fill")
                                    .foregroundColor(.accentColor)
                                
                                Text("\(dateFormatter.string(from: bookmark.lastUpdated))")
                                    .font(.body)
                                
                                Spacer()
                                
                                Text("Page \(bookmark.page)")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            .padding()
                            .background(Color(.systemBackground))
                            .cornerRadius(8)
                            .shadow(color: .black.opacity(0.05), radius: 2, x: 0, y: 1)
                        }
                    }
                }
                .padding()
                .background(Color(.secondarySystemBackground).opacity(0.5))
                .cornerRadius(12)
                
                Button("Sign Out") {
                    viewModel.kt.logout()
                }
                .foregroundColor(.red)
            }
            .padding()
        }
        .task {
            // Load bookmarks
            do {
                for try await list in DatabaseManager.shared.bookmarksSequence() {
                    bookmarks = list
                }
            }
            catch {
                print("Error fetching bookmarks: \(error)")
            }
            
        }
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

#Preview {
    AuthView(viewModel: ObservableViewModel(Shared.AuthViewModel()) { _, _ in [] })
}

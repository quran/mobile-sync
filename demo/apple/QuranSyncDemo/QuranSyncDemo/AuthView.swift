import SwiftUI
import Shared
import KMPNativeCoroutinesAsync

/**
 * Authentication screen for iOS demo app.
 */
struct AuthView: View {
    @ObservedObject var viewModel: SyncViewModel
    
    var onAuthenticationSuccess: () -> Void = {}

    var body: some View {
        ZStack {
            // Background
            Color(.systemBackground)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                // Header (Only show when not in success state or combine with success)
                if !(viewModel.authState is Shared.AuthState.Success) {
                    VStack(spacing: 8) {
                        Text("Quran.com Sync")
                            .font(.largeTitle)
                            .fontWeight(.bold)

                        Text("Sign in with Quran Foundation")
                            .font(.body)
                            .foregroundColor(.secondary)
                    }
                    .padding(.top, 32)
                    .padding(.bottom, 32)
                }

                // Content based on auth state
                Group {
                    let state = viewModel.authState
                    if state is Shared.AuthState.Idle {
                        LoginButtonContent(
                            onLogin: {
                                try? await viewModel.login()
                            },
                            onReauthenticateLogin: {
                                try? await viewModel.loginWithReauthentication()
                            }
                        )
                        .padding(.horizontal, 16)
                    } else if state is Shared.AuthState.Loading {
                        LoadingContent()
                    } else if let successState = state as? Shared.AuthState.Success {
                        SuccessTabView(viewModel: viewModel, userInfo: successState.userInfo)
                    } else if let errorState = state as? Shared.AuthState.Error {
                        ErrorContent(
                            message: errorState.message,
                            onDismiss: { viewModel.clearError() },
                            onRetry: { try? await viewModel.login() }
                        )
                        .padding(.horizontal, 16)
                    }
                }

                Spacer()
            }
        }
        .onChange(of: viewModel.authState) { _, newState in
            if newState is Shared.AuthState.Success {
                onAuthenticationSuccess()
            }
        }
        .task {
            // Observe data flows in the background
            await viewModel.observeData()
        }
    }
}

struct SuccessTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    let userInfo: Shared.UserInfo?
    @State private var selectedTab = 0
    @State private var clearLocalData = false
    
    var body: some View {
        VStack(spacing: 0) {
            // Mini Header
            HStack {
                VStack(alignment: .leading) {
                    Text("Welcome, \(userInfo?.displayName ?? "User")!")
                        .font(.headline)
                    if let email = userInfo?.email {
                        Text(email)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Toggle("Clear local data", isOn: $clearLocalData)
                        .font(.caption)
                        .toggleStyle(SwitchToggleStyle(tint: .red))
                    
                    Button(action: {
                        Task {
                            try? await viewModel.logout(clearLocalData: clearLocalData)
                        }
                    }) {
                        HStack {
                            Text("Sign Out")
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                        }
                        .foregroundColor(.red)
                    }
                }
            }
            .padding()
            .background(Color(.secondarySystemBackground))

            TabView(selection: $selectedTab) {
                BookmarksTabView(viewModel: viewModel, readingBookmark: viewModel.readingBookmark)
                    .tabItem {
                        Label("Bookmarks", systemImage: "bookmark")
                    }
                    .tag(0)

                CollectionsTabView(viewModel: viewModel)
                    .tabItem {
                        Label("Collections", systemImage: "folder")
                    }
                    .tag(1)

                NotesTabView(viewModel: viewModel)
                    .tabItem {
                        Label("Notes", systemImage: "note.text")
                    }
                    .tag(2)
                    
                ReadingSessionsTabView(viewModel: viewModel)
                    .tabItem {
                        Label("Reading", systemImage: "clock")
                    }
                    .tag(3)
            }
        }
    }
}

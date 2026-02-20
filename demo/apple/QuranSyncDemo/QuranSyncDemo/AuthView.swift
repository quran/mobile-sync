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
                        LoginButtonContent {
                            try? await viewModel.login()
                        }
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
    let userInfo: Shared.UserInfo
    @State private var selectedTab = 0
    
    var body: some View {
        VStack(spacing: 0) {
            // Mini Header
            HStack {
                VStack(alignment: .leading) {
                    Text("Welcome, \(userInfo.displayName ?? "User")!")
                        .font(.headline)
                    if let email = userInfo.email {
                        Text(email)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                Spacer()
                Button(action: {
                    Task {
                        try? await viewModel.logout()
                    }
                }) {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                        .foregroundColor(.red)
                }
            }
            .padding()
            .background(Color(.secondarySystemBackground))

            TabView(selection: $selectedTab) {
                BookmarksTabView(viewModel: viewModel)
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
            }
        }
    }
}

import Foundation
import Shared
import KMPNativeCoroutinesAsync
import Combine

/**
 * Native iOS ViewModel for Authentication.
 * 
 * This class wraps the shared managed SyncAuthService and provides it to SwiftUI.
 * It uses KMP-NativeCoroutines to convert Kotlin Flows into Swift AsyncSequences.
 */
@MainActor
class AuthViewModel: ObservableObject {
    private let authService: SyncAuthService
    private let syncService: SyncService

    @Published var authState: AuthState = AuthState.Idle()

    init(
        authService: SyncAuthService,
        syncService: SyncService
    ) {
        self.authService = authService
        self.syncService = syncService
    }

    func bookmarksSequence() -> any AsyncSequence<[AyahBookmark], Error> {
        return asyncSequence(for: syncService.bookmarks)
    }

    func observeAuthState() async {
        // Observe authState using KMP-NativeCoroutines
        do {
            for try await state in asyncSequence(for: authService.authStateFlow) {
                self.authState = state
            }
        } catch {
            print("AuthViewModel: Error observing authStateFlow: \(error)")
        }
    }

    func login() async throws {
        try await asyncFunction(for: authService.login())
    }

    func loginWithReauthentication() async throws {
        try await asyncFunction(for: authService.loginWithReauthentication())
    }


    func logout() async throws {
        try await asyncFunction(for: authService.logout(clearLocalData: true))
    }

    func clearError() {
        authService.clearError()
    }


    func isLoggedIn() -> Bool {
        return authService.isLoggedIn()
    }
}

import Foundation
import Shared
import KMPNativeCoroutinesAsync
import Combine

/**
 * Native iOS ViewModel for Sync and Data.
 * 
 * This class wraps the shared SyncService and provides it to SwiftUI.
 * It uses KMP-NativeCoroutines to convert Kotlin Flows into Swift AsyncSequences.
 */
@MainActor
class SyncViewModel: ObservableObject {
    private let service: SyncService
    private let authService: AuthService

    @Published var authState: AuthState = AuthState.Idle()
    @Published var bookmarks: [Shared.Bookmark] = []

    init(authService: AuthService, service: SyncService) {
        self.authService = authService
        self.service = service
    }

    func observeData() async {
        // Observe authState
        let authTask = Task {
            do {
                for try await state in asyncSequence(for: service.authStateFlow) {
                    self.authState = state
                }
            } catch {
                print("SyncViewModel: Error observing authState: \(error)")
            }
        }
        
        // Observe bookmarks
        let bookmarksTask = Task {
            do {
                for try await list in asyncSequence(for: service.bookmarks) {
                    self.bookmarks = list as [Shared.Bookmark]
                }
            } catch {
                print("SyncViewModel: Error observing bookmarksFlow: \(error)")
            }
        }
    }

    func triggerSync() {
        service.triggerSync()
    }

    func addBookmark(page: Int32) {
        Task {
            do {
                try await asyncFunction(for: service.addBookmark(page: page))
            } catch {
                print("SyncViewModel: Failed to add bookmark: \(error)")
            }
        }
    }

    func login() async throws {
        try await asyncFunction(for: authService.login())
    }

    func logout() async throws {
        try await asyncFunction(for: authService.logout())
    }

    func clearError() {
        authService.clearError()
    }

    func observeAuthState() async {
        // No-op for now if not needed, or implement if view needs to trigger it
    }
}

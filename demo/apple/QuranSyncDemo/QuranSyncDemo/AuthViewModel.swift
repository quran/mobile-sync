import Foundation
import Shared
import KMPNativeCoroutinesAsync
import Combine

/**
 * Native iOS ViewModel for Authentication.
 * 
 * This class wraps the shared AuthCore.AuthService and provides it to SwiftUI.
 * It uses KMP-NativeCoroutines to convert Kotlin Flows into Swift AsyncSequences.
 */
@MainActor
class AuthViewModel: ObservableObject {
    private let authService: AuthService
    private let bookmarksRepository: BookmarksRepository

    @Published var authState: AuthState = AuthState.Idle()

    init(
        authService: AuthService,
        bookmarksRepository: BookmarksRepository
    ) {
        self.authService = authService
        self.bookmarksRepository = bookmarksRepository
    }

    func bookmarksSequence() -> any AsyncSequence<[Bookmark.PageBookmark], Error> {
        // todo: PR comment create getPageBookmarks() method in the KMP library
        return asyncSequence(for: bookmarksRepository.getBookmarksFlow()).map { bookmarks in
            bookmarks.compactMap {
                $0 as? Bookmark.PageBookmark
            }
        }
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


    func logout() async throws {
        try await asyncFunction(for: authService.logout())
    }

    func clearError() {
        authService.clearError()
    }


    func isLoggedIn() -> Bool {
        return authService.isLoggedIn()
    }
}

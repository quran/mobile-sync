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
    
    init(authService: AuthService = AuthConfigFactory.shared.authService) {
        self.authService = authService
        // Initialize bookmarks repository for the sequence helper
        let driverFactory = DriverFactory()
        self.bookmarksRepository = BookmarksRepositoryFactory.shared.createRepository(driverFactory: driverFactory)
        setupObservations()
    }
    
    func bookmarksSequence() -> any AsyncSequence<[Bookmark.PageBookmark], Error> {
        // todo: PR comment create getPageBookmarks() method in the KMP library
        return asyncSequence(for: bookmarksRepository.getBookmarksFlow()).map { bookmarks in
            bookmarks.compactMap { $0 as? Bookmark.PageBookmark }
        }
    }
    
    private func setupObservations() {
        // Observe authState using KMP-NativeCoroutines
        Task {
            do {
                for try await state in asyncSequence(for: authService.authStateFlow) {
                    self.authState = state
                }
            } catch {
                print("AuthViewModel: Error observing authStateFlow: \(error)")
            }
        }
    }
    
    /**
     * Initiates the OAuth login flow.
     */
    func login() {
        Task {
            do {
                try await asyncFunction(for: authService.login())
            } catch {
                print("AuthViewModel: Login error: \(error)")
            }
        }
    }
    
    /**
     * Signs out the user.
     */
    func logout() {
        Task {
            do {
                try await asyncFunction(for: authService.logout())
            } catch {
                print("AuthViewModel: Logout error: \(error)")
            }
        }
    }
    
    /**
     * Resets the error state.
     */
    func clearError() {
        authService.clearError()
    }
    
    /**
     * Check if user is currently logged in.
     */
    func isLoggedIn() -> Bool {
        return authService.isLoggedIn()
    }
}

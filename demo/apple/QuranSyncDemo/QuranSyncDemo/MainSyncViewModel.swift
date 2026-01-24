import Foundation
import Shared
import KMPNativeCoroutinesAsync
import Combine

/**
 * Native iOS ViewModel for Sync and Data.
 * 
 * This class wraps the shared MainSyncService and provides it to SwiftUI.
 * It uses KMP-NativeCoroutines to convert Kotlin Flows into Swift AsyncSequences.
 */
@MainActor
class MainSyncViewModel: ObservableObject {
    private let service: MainSyncService
    let authViewModel: AuthViewModel

    @Published var authState: AuthState = AuthState.Idle()
    @Published var bookmarks: [Shared.Bookmark] = []

    init(authViewModel: AuthViewModel, service: MainSyncService) {
        self.authViewModel = authViewModel
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
                print("MainSyncViewModel: Error observing authState: \(error)")
            }
        }
        
        // Observe bookmarks
        let bookmarksTask = Task {
            do {
                for try await list in asyncSequence(for: service.bookmarks) {
                    self.bookmarks = list as [Shared.Bookmark]
                }
            } catch {
                print("MainSyncViewModel: Error observing bookmarksFlow: \(error)")
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
                print("MainSyncViewModel: Failed to add bookmark: \(error)")
            }
        }
    }
}

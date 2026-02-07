import Foundation
import Shared
import KMPNativeCoroutinesAsync

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
    @Published var collections: [Shared.Collection_] = []
    @Published var notes: [Shared.Note_] = []

    init(authService: AuthService, service: SyncService) {
        self.authService = authService
        self.service = service
    }

    func observeData() async {
        // Observe authState
        let _ = Task {
            do {
                for try await state in asyncSequence(for: service.authStateFlow) {
                    self.authState = state
                }
            } catch {
                print("SyncViewModel: Error observing authState: \(error)")
            }
        }
        
        // Observe bookmarks
        let _ = Task {
            do {
                for try await list in asyncSequence(for: service.bookmarks) {
                    self.bookmarks = list as [Shared.Bookmark]
                }
            } catch {
                print("SyncViewModel: Error observing bookmarks: \(error)")
            }
        }
        
        // Observe collections
        let _ = Task {
            do {
                for try await list in asyncSequence(for: service.collections) {
                    self.collections = list as [Shared.Collection_]
                }
            } catch {
                print("SyncViewModel: Error observing collections: \(error)")
            }
        }
        
        // Observe notes
        let _ = Task {
            do {
                for try await list in asyncSequence(for: service.notes) {
                    self.notes = list as [Shared.Note_]
                }
            } catch {
                print("SyncViewModel: Error observing notes: \(error)")
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
                print("SyncViewModel: Failed to add page bookmark: \(error)")
            }
        }
    }

    func addBookmark(sura: Int32, ayah: Int32) {
        Task {
            do {
                try await asyncFunction(for: service.addBookmark(sura: sura, ayah: ayah))
            } catch {
                print("SyncViewModel: Failed to add ayah bookmark: \(error)")
            }
        }
    }

    func deleteBookmark(bookmark: Shared.Bookmark) {
        Task {
            do {
                try await asyncFunction(for: service.deleteBookmark(bookmark: bookmark))
            } catch {
                print("SyncViewModel: Failed to delete bookmark: \(error)")
            }
        }
    }

    func addCollection(name: String) {
        Task {
            do {
                try await asyncFunction(for: service.addCollection(name: name))
            } catch {
                print("SyncViewModel: Failed to add collection: \(error)")
            }
        }
    }

    func deleteCollection(localId: String) {
        Task {
            do {
                try await asyncFunction(for: service.deleteCollection(localId: localId))
            } catch {
                print("SyncViewModel: Failed to delete collection: \(error)")
            }
        }
    }

    func addNote(body: String, startAyahId: Int64, endAyahId: Int64) {
        Task {
            do {
                try await asyncFunction(for: service.addNote(body: body, startAyahId: startAyahId, endAyahId: endAyahId))
            } catch {
                print("SyncViewModel: Failed to add note: \(error)")
            }
        }
    }

    func deleteNote(localId: String) {
        Task {
            do {
                try await asyncFunction(for: service.deleteNote(localId: localId))
            } catch {
                print("SyncViewModel: Failed to delete note: \(error)")
            }
        }
    }

    func addBookmarkToCollection(collectionId: String, bookmark: Shared.Bookmark) {
        Task {
            do {
                try await asyncFunction(for: service.addBookmarkToCollection(collectionLocalId: collectionId, bookmark: bookmark))
            } catch {
                print("SyncViewModel: Failed to add bookmark to collection: \(error)")
            }
        }
    }

    func removeBookmarkFromCollection(collectionId: String, bookmark: Shared.Bookmark) {
        Task {
            do {
                try await asyncFunction(for: service.removeBookmarkFromCollection(collectionLocalId: collectionId, bookmark: bookmark))
            } catch {
                print("SyncViewModel: Failed to remove bookmark from collection: \(error)")
            }
        }
    }

    func bookmarksForCollection(collectionId: String) -> any AsyncSequence {
        return asyncSequence(for: service.getBookmarksForCollectionFlow(collectionLocalId: collectionId))
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

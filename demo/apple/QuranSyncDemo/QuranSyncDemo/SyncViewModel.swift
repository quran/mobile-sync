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
    private let syncService: SyncService
    private let authService: AuthService

    @Published var authState: AuthState = AuthState.Idle()
    @Published var bookmarks: [Shared.Bookmark] = []
    @Published var collectionsWithBookmarks: [Shared.CollectionWithBookmarks] = []
    @Published var notes: [Shared.Note_] = []

    init(authService: AuthService, syncService: SyncService) {
        self.authService = authService
        self.syncService = syncService
    }

    deinit {
        syncService.clear()
    }

    func observeData() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                await self.observeAuthState()
            }
            group.addTask {
                await self.observeBookmarks()
            }
            group.addTask {
                await self.observeCollections()
            }
            group.addTask {
                await self.observeNotes()
            }
        }
    }

    private func observeAuthState() async {
        do {
            for try await state in asyncSequence(for: syncService.authStateFlow) {
                self.authState = state
            }
        } catch {
            print("SyncViewModel: Error observing authState: \(error)")
        }
    }

    private func observeBookmarks() async {
        do {
            for try await list in asyncSequence(for: syncService.bookmarks) {
                self.bookmarks = list as [Shared.Bookmark]
            }
        } catch {
            print("SyncViewModel: Error observing bookmarks: \(error)")
        }
    }

    private func observeCollections() async {
        do {
            for try await list in asyncSequence(for: syncService.collectionsWithBookmarks) {
                self.collectionsWithBookmarks = list as [Shared.CollectionWithBookmarks]
            }
        } catch {
            print("SyncViewModel: Error observing collections: \(error)")
        }
    }

    private func observeNotes() async {
        do {
            for try await list in asyncSequence(for: syncService.notes) {
                self.notes = list as [Shared.Note_]
            }
        } catch {
            print("SyncViewModel: Error observing notes: \(error)")
        }
    }

    func triggerSync() {
        syncService.triggerSync()
    }

    func addBookmark(page: Int32) async -> Shared.Bookmark? {
        do {
            return try await asyncFunction(for: syncService.addBookmark(page: page))
        } catch {
            print("SyncViewModel: Failed to add page bookmark: \(error)")
            return nil
        }
    }

    func addBookmark(sura: Int32, ayah: Int32) async -> Shared.Bookmark? {
        do {
            return try await asyncFunction(for: syncService.addBookmark(sura: sura, ayah: ayah))
        } catch {
            print("SyncViewModel: Failed to add ayah bookmark: \(error)")
            return nil
        }
    }

    func deleteBookmark(bookmark: Shared.Bookmark) async {
        do {
            try await asyncFunction(for: syncService.deleteBookmark(bookmark: bookmark))
        } catch {
            print("SyncViewModel: Failed to delete bookmark: \(error)")
        }
    }

    func addCollection(name: String) async {
        do {
            try await asyncFunction(for: syncService.addCollection(name: name))
        } catch {
            print("SyncViewModel: Failed to add collection: \(error)")
        }
    }

    func deleteCollection(collectionId: String) async {
        do {
            try await asyncFunction(for: syncService.deleteCollection(localId: collectionId))
        } catch {
            print("SyncViewModel: Failed to delete collection: \(error)")
        }
    }

    func addNote(body: String, startAyahId: Int64, endAyahId: Int64) async {
        do {
            try await asyncFunction(for: syncService.addNote(body: body, startAyahId: startAyahId, endAyahId: endAyahId))
        } catch {
            print("SyncViewModel: Failed to add note: \(error)")
        }
    }

    func deleteNote(localId: String) async {
        do {
            try await asyncFunction(for: syncService.deleteNote(localId: localId))
        } catch {
            print("SyncViewModel: Failed to delete note: \(error)")
        }
    }

    func addBookmarkToCollection(collectionId: String, bookmark: Shared.Bookmark) async {
        do {
            try await asyncFunction(for: syncService.addBookmarkToCollection(collectionLocalId: collectionId, bookmark: bookmark))
        } catch {
            print("SyncViewModel: Failed to add bookmark to collection: \(error)")
        }
    }

    func removeBookmarkFromCollection(collectionId: String, bookmark: Shared.Bookmark) async {
        do {
            try await asyncFunction(for: syncService.removeBookmarkFromCollection(collectionLocalId: collectionId, bookmark: bookmark))
        } catch {
            print("SyncViewModel: Failed to remove bookmark from collection: \(error)")
        }
    }

    func addAyahBookmarkToCollection(collectionId: String, sura: Int32, ayah: Int32) async {
        do {
            let bookmark = try await asyncFunction(for: syncService.addBookmark(sura: sura, ayah: ayah))
            try await asyncFunction(for: syncService.addBookmarkToCollection(collectionLocalId: collectionId, bookmark: bookmark))
        } catch {
            print("SyncViewModel: Failed to add random bookmark to collection: \(error)")
        }
    }

    func bookmarksForCollection(collectionId: String) -> any AsyncSequence {
        return asyncSequence(for: syncService.getBookmarksForCollectionFlow(collectionLocalId: collectionId))
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
}
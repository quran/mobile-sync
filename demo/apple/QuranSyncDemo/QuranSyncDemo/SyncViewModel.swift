import Foundation
import Shared
import KMPNativeCoroutinesAsync

/**
 * Native iOS ViewModel for Sync and Data.
 *
 * This class wraps the shared QuranDataService and provides it to SwiftUI.
 * It uses KMP-NativeCoroutines to convert Kotlin Flows into Swift AsyncSequences.
 */
@MainActor
class SyncViewModel: ObservableObject {
    private let quranDataService: QuranDataService
    private let authService: SyncAuthService

    @Published var authState: AuthState = AuthState.Idle()
    @Published var bookmarks: [Shared.AyahBookmark] = []
    @Published var readingBookmark: Shared.ReadingBookmark? = nil
    @Published var collectionsWithBookmarks: [Shared.CollectionWithAyahBookmarks] = []
    @Published var notes: [Shared.Note_] = []
    @Published var readingSessions: [Shared.ReadingSession] = []

    init(authService: SyncAuthService, quranDataService: QuranDataService) {
        self.authService = authService
        self.quranDataService = quranDataService
    }

    func observeData() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor [weak self] in
                guard let quranDataService = self?.quranDataService else {
                    return
                }
                do {
                    for try await state in asyncSequence(for: quranDataService.authStateFlow) {
                        guard let self = self else {
                            break
                        }
                        self.authState = state
                    }
                } catch {
                    print("SyncViewModel: Error observing authState: \(error)")
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let quranDataService = self?.quranDataService else {
                    return
                }
                do {
                    for try await list in asyncSequence(for: quranDataService.bookmarks) {
                        guard let self = self else {
                            break
                        }
                        self.bookmarks = list as [Shared.AyahBookmark]
                    }
                } catch {
                    print("SyncViewModel: Error observing bookmarks: \(error)")
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let quranDataService = self?.quranDataService else {
                    return
                }
                do {
                    for try await bookmark in asyncSequence(for: quranDataService.readingBookmark) {
                        guard let self = self else {
                            break
                        }
                        self.readingBookmark = bookmark
                    }
                } catch {
                    print("SyncViewModel: Error observing reading bookmark: \(error)")
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let quranDataService = self?.quranDataService else {
                    return
                }
                do {
                    for try await list in asyncSequence(for: quranDataService.collectionsWithBookmarks) {
                        guard let self = self else {
                            break
                        }
                        self.collectionsWithBookmarks = list as [Shared.CollectionWithAyahBookmarks]
                    }
                } catch {
                    print("SyncViewModel: Error observing collections: \(error)")
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let quranDataService = self?.quranDataService else {
                    return
                }
                do {
                    for try await list in asyncSequence(for: quranDataService.notes) {
                        guard let self = self else {
                            break
                        }
                        self.notes = list as [Shared.Note_]
                    }
                } catch {
                    print("SyncViewModel: Error observing notes: \(error)")
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let quranDataService = self?.quranDataService else {
                    return
                }
                do {
                    for try await list in asyncSequence(for: quranDataService.readingSessions) {
                        guard let self = self else {
                            break
                        }
                        self.readingSessions = list as [Shared.ReadingSession]
                    }
                } catch {
                    print("SyncViewModel: Error observing reading sessions: \(error)")
                }
            }
        }
    }

    func triggerSync() {
        quranDataService.triggerSync()
    }

    func addBookmark(sura: Int32, ayah: Int32) async -> Shared.AyahBookmark? {
        do {
            return try await asyncFunction(for: quranDataService.addBookmark(sura: sura, ayah: ayah))
        } catch {
            print("SyncViewModel: Failed to add ayah bookmark: \(error)")
            return nil
        }
    }

    func deleteBookmark(bookmark: Shared.AyahBookmark) async {
        do {
            try await asyncFunction(for: quranDataService.deleteBookmark(bookmark: bookmark))
        } catch {
            print("SyncViewModel: Failed to delete bookmark: \(error)")
        }
    }

    func addCollection(name: String) async {
        do {
            try await asyncFunction(for: quranDataService.addCollection(name: name))
        } catch {
            print("SyncViewModel: Failed to add collection: \(error)")
        }
    }

    func deleteCollection(collectionId: String) async {
        do {
            try await asyncFunction(for: quranDataService.deleteCollection(localId: collectionId))
        } catch {
            print("SyncViewModel: Failed to delete collection: \(error)")
        }
    }

    func addNote(body: String, startSura: Int32, startAyah: Int32, endSura: Int32, endAyah: Int32) async {
        do {
            try await asyncFunction(
                for: quranDataService.addNote(
                    body: body,
                    startSura: startSura,
                    startAyah: startAyah,
                    endSura: endSura,
                    endAyah: endAyah
                )
            )
        } catch {
            print("SyncViewModel: Failed to add note: \(error)")
        }
    }

    func deleteNote(localId: String) async {
        do {
            try await asyncFunction(for: quranDataService.deleteNote(localId: localId))
        } catch {
            print("SyncViewModel: Failed to delete note: \(error)")
        }
    }

    func addBookmarkToCollection(collectionId: String, bookmark: Shared.AyahBookmark) async {
        do {
            try await asyncFunction(for: quranDataService.addBookmarkToCollection(collectionLocalId: collectionId, bookmark: bookmark))
        } catch {
            print("SyncViewModel: Failed to add bookmark to collection: \(error)")
        }
    }

    func removeBookmarkFromCollection(collectionId: String, bookmark: Shared.AyahBookmark) async {
        do {
            try await asyncFunction(for: quranDataService.removeBookmarkFromCollection(collectionLocalId: collectionId, bookmark: bookmark))
        } catch {
            print("SyncViewModel: Failed to remove bookmark from collection: \(error)")
        }
    }

    func addAyahBookmarkToCollection(collectionId: String, sura: Int32, ayah: Int32) async {
        do {
            try await asyncFunction(for: quranDataService.addAyahBookmarkToCollection(collectionLocalId: collectionId, sura: sura, ayah: ayah))
        } catch {
            print("SyncViewModel: Failed to add random bookmark to collection: \(error)")
        }
    }

    func bookmarksForCollection(collectionId: String) -> any AsyncSequence {
        return asyncSequence(for: quranDataService.getBookmarksForCollectionFlow(collectionLocalId: collectionId))
    }

    func login() async throws {
        try await asyncFunction(for: authService.login())
    }

    func loginWithReauthentication() async throws {
        try await asyncFunction(for: authService.loginWithReauthentication())
    }

    func addReadingBookmark(sura: Int32, ayah: Int32) async -> Shared.ReadingBookmark? {
        do {
            return try await asyncFunction(for: quranDataService.addAyahReadingBookmark(sura: sura, ayah: ayah))
        } catch {
            print("SyncViewModel: Failed to add current reading ayah bookmark: \(error)")
            return nil
        }
    }

    func addPageReadingBookmark(page: Int32) async -> Shared.ReadingBookmark? {
        do {
            return try await asyncFunction(for: quranDataService.addPageReadingBookmark(page: page))
        } catch {
            print("SyncViewModel: Failed to add current reading page bookmark: \(error)")
            return nil
        }
    }

    func addReadingSession(sura: Int32, ayah: Int32) async -> Shared.ReadingSession? {
        do {
            return try await asyncFunction(for: quranDataService.addReadingSession(sura: sura, ayah: ayah))
        } catch {
            print("SyncViewModel: Failed to add reading session: \(error)")
            return nil
        }
    }

    func deleteReadingBookmark() async {
        do {
            _ = try await asyncFunction(for: quranDataService.deleteReadingBookmark())
        } catch {
            print("SyncViewModel: Failed to delete current reading bookmark: \(error)")
        }
    }

    func logout(clearLocalData: Bool = true) async throws {
        try await asyncFunction(for: authService.logout(clearLocalData: clearLocalData))
    }

    func clearError() {
        authService.clearError()
    }
}

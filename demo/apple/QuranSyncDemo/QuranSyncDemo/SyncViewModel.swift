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
    @Published var readingBookmarks: [Shared.ReadingBookmark] = []
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
                    for try await list in asyncSequence(for: quranDataService.readingBookmarks) {
                        guard let self = self else {
                            break
                        }
                        self.readingBookmarks = list as [Shared.ReadingBookmark]
                    }
                } catch {
                    print("SyncViewModel: Error observing reading bookmarks: \(error)")
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

    func addCollection(name: String) async {
        do {
            try await asyncFunction(for: quranDataService.addCollection(name: name))
        } catch {
            print("SyncViewModel: Failed to add collection: \(error)")
        }
    }

    func deleteCollection(collectionId: String) async {
        do {
            try await asyncFunction(for: quranDataService.deleteCollection(id: collectionId))
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

    func deleteNote(id: String) async {
        do {
            try await asyncFunction(for: quranDataService.deleteNote(id: id))
        } catch {
            print("SyncViewModel: Failed to delete note: \(error)")
        }
    }

    func addAyahBookmarkToCollection(collectionId: String, sura: Int32, ayah: Int32) async {
        do {
            try await asyncFunction(for: quranDataService.addAyahBookmarkToCollection(collectionId: collectionId, sura: sura, ayah: ayah))
        } catch {
            print("SyncViewModel: Failed to add random bookmark to collection: \(error)")
        }
    }

    func login() async throws {
        try await asyncFunction(for: authService.login())
    }

    func loginWithReauthentication() async throws {
        try await asyncFunction(for: authService.loginWithReauthentication())
    }

    func setAyahReadingBookmark(
        slot: Shared.ReadingBookmarkSlot,
        sura: Int32,
        ayah: Int32
    ) async -> Shared.ReadingBookmark? {
        do {
            return try await asyncFunction(
                for: quranDataService.setAyahReadingBookmark(slot: slot, sura: sura, ayah: ayah)
            )
        } catch {
            print("SyncViewModel: Failed to set reading ayah bookmark: \(error)")
            return nil
        }
    }

    func setPageReadingBookmark(
        slot: Shared.ReadingBookmarkSlot,
        page: Int32
    ) async -> Shared.ReadingBookmark? {
        do {
            return try await asyncFunction(for: quranDataService.setPageReadingBookmark(slot: slot, page: page))
        } catch {
            print("SyncViewModel: Failed to set reading page bookmark: \(error)")
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

    func clearReadingBookmark(slot: Shared.ReadingBookmarkSlot) async {
        do {
            _ = try await asyncFunction(for: quranDataService.clearReadingBookmark(slot: slot))
        } catch {
            print("SyncViewModel: Failed to clear reading bookmark: \(error)")
        }
    }

    func logout(clearLocalData: Bool = true) async throws {
        try await asyncFunction(for: authService.logout(clearLocalData: clearLocalData))
    }

    func clearError() {
        authService.clearError()
    }
}

import Foundation
import KMPNativeCoroutinesAsync
import Shared

@MainActor
class DatabaseManager {
    static let shared = DatabaseManager()

    let syncService: SyncService
    let syncViewModel: SyncViewModel

    private init() {
        let driverFactory = DriverFactory()
        let environment = SynchronizationEnvironment(endPointURL: "https://apis-prelive.quran.foundation/auth") // todo configure url env
        self.syncService = SyncPipelineFactory.shared.createSyncService(driverFactory: driverFactory, environment: environment)
        
        self.syncViewModel = SyncViewModel(
            authService: AuthConfigFactory.shared.authService,
            service: self.syncService
        )
    }

    func bookmarksSequence() -> any AsyncSequence<[Shared.Bookmark.PageBookmark], Error> {
        return asyncSequence(for: syncService.bookmarks).map { bookmarks in
            (bookmarks as [Shared.Bookmark]).compactMap {
                $0 as? Shared.Bookmark.PageBookmark
            }
        }
    }

    // Add a bookmark for a given page using async/await bridge.
    func addPageBookmark(page: Int) async throws {
        try await asyncFunction(for: syncService.addBookmark(page: Int32(page)))
    }

    // Add a random bookmark using async/await bridge.
    func addRandomBookmark() async throws {
        let randomPage = Int.random(in: 1...604)
        try await addPageBookmark(page: randomPage)
    }
}


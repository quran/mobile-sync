import Foundation
import KMPNativeCoroutinesAsync
import Shared

@MainActor
class DatabaseManager {
    static let shared = DatabaseManager()

    let syncService: MainSyncService
    let mainViewModel: MainSyncViewModel

    private init() {
        let driverFactory = DriverFactory()
        let database = DriverFactoryKt.makeDatabase(driverFactory: driverFactory)
        
        let bookmarksRepository = BookmarksRepositoryImpl(database: database)
        let collectionsRepository = CollectionsRepositoryImpl(database: database)
        let collectionBookmarksRepository = CollectionBookmarksRepositoryImpl(database: database)
        
        let pipeline = SyncEnginePipeline(
            bookmarksRepository: bookmarksRepository as BookmarksSynchronizationRepository,
            collectionsRepository: collectionsRepository as CollectionsSynchronizationRepository,
            collectionBookmarksRepository: collectionBookmarksRepository as CollectionBookmarksSynchronizationRepository,
            notesRepository: nil
        )
        
        let authService = AuthConfigFactory.shared.authService
        self.syncService = MainSyncService(
            authService: authService,
            pipeline: pipeline,
            environment: SynchronizationEnvironment(endPointURL: "https://apis-prelive.quran.foundation/auth"), // todo configure url env
            settings: MainSyncServiceKt.makeSettings()
        )
        
        self.mainViewModel = MainSyncViewModel(
            authViewModel: AuthViewModel(authService: authService),
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


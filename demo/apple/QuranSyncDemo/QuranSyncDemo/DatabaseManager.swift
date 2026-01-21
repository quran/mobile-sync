//
//  DatabaseManager.swift
//  QuranSyncDemo
//
//  Created by Claude AI on 5/18/25.
//

import Foundation
import KMPNativeCoroutinesAsync
import Shared

class DatabaseManager {
  static let shared = DatabaseManager()
  
  private let bookmarksRepository: BookmarksRepository
  
  private init() {
    let driverFactory = DriverFactory()
    self.bookmarksRepository = BookmarksRepositoryFactory.shared.createRepository(driverFactory: driverFactory)
  }
  
  func bookmarksSequence() -> any AsyncSequence<[Bookmark.PageBookmark], Error> {
    // todo: PR comment create getPageBookmarks() method in the KMP library
      return asyncSequence(for: bookmarksRepository.getBookmarksFlow()).map { bookmarks in
      bookmarks.compactMap { $0 as? Bookmark.PageBookmark }
    }
  }
  
  // Add a bookmark for a given page using async/await bridge.
  func addPageBookmark(page: Int) async throws {
    try await asyncFunction(for: bookmarksRepository.addBookmark(page: Int32(page)))
  }
  
  // Add a random bookmark using async/await bridge.
  func addRandomBookmark() async throws {
    let randomPage = Int.random(in: 1...604)
    try await addPageBookmark(page: randomPage)
  }
}

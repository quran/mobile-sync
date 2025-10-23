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
  
  private let pageBookmarksRepository: PageBookmarksRepository
  
  private init() {
    let driverFactory = DriverFactory()
    self.pageBookmarksRepository = PageBookmarksRepositoryFactory.companion.createRepository(driverFactory: driverFactory)
  }
  
  func bookmarksSequence() -> any AsyncSequence<[PageBookmark], Error> {
    asyncSequence(for: pageBookmarksRepository.getAllBookmarks())
  }
  
  // Add a bookmark for a given page using async/await bridge.
  func addPageBookmark(page: Int) async throws {
    try await asyncFunction(for: pageBookmarksRepository.addPageBookmark(page: Int32(page)))
  }
  
  // Add a random bookmark using async/await bridge.
  func addRandomBookmark() async throws {
    let randomPage = Int.random(in: 1...604)
    try await addPageBookmark(page: randomPage)
  }
}

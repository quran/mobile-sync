//
//  DatabaseManager.swift
//  QuranSyncDemo
//
//  Created by Claude AI on 5/18/25.
//

import Foundation
import Shared

class DatabaseManager {
  static let shared = DatabaseManager()
  
  private let database: QuranDatabase
  
  private init() {
    let driverFactory = DriverFactory()
    self.database = DriverFactoryKt.makeDatabase(driverFactory: driverFactory)
  }
  
  // Provides access to bookmark queries
  var bookmarkQueries: BookmarksQueries {
    return database.bookmarksQueries
  }
  
  // Get all bookmarks
  func getPageBookmarks() -> [Page_bookmark] {
    return bookmarkQueries.getBookmarks().executeAsList()
  }
  
  // Add a new bookmark
  func addPageBookmark(page: Int64) {
    bookmarkQueries.addNewBookmark(page: page)
  }
  
  // Add a random bookmark
  func addRandomBookmark() {
    let randomPage = Int64.random(in: 1...604)
    addPageBookmark(page: randomPage)
  }
}

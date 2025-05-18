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
  func getAllBookmarks() -> [Bookmarks] {
    return bookmarkQueries.getBookmarks().executeAsList()
  }
  
  // Add a new bookmark
  func addBookmark(sura: Int64, ayah: Int64) {
    bookmarkQueries.addBookmark(sura: sura, ayah: ayah)
  }
  
  // Add a random bookmark
  func addRandomBookmark() {
    let randomSura = Int64.random(in: 1...114)
    let randomAyah = Int64.random(in: 1...286) // Max ayah count is 286 in Quran
    addBookmark(sura: randomSura, ayah: randomAyah)
  }
}

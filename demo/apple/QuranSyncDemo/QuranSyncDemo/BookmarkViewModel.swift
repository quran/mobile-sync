//
//  BookmarkViewModel.swift
//  QuranSyncDemo
//
//  Created by Claude AI on 5/18/25.
//

import Foundation
import Shared
import Combine

class BookmarkViewModel: ObservableObject {
  @Published var bookmarks: [Bookmarks] = []
  
  private let databaseManager = DatabaseManager.shared
  
  init() {
    loadBookmarks()
  }
  
  func loadBookmarks() {
    bookmarks = databaseManager.getAllBookmarks()
  }
  
  func addRandomBookmark() {
    databaseManager.addRandomBookmark()
    loadBookmarks() // Refresh the list after adding
  }
  
  // Format bookmark for display
  func formatBookmark(_ bookmark: Bookmarks) -> String {
    return "Sura \(bookmark.sura), Ayah \(bookmark.ayah)"
  }
  
  // Format the timestamp
  func formatTimestamp(_ timestamp: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(timestamp))
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .short
    return formatter.string(from: date)
  }
}

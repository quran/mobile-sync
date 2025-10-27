//
//  BookmarkViewModel.swift
//  QuranSyncDemo
//
//  Created by Claude AI on 5/18/25.
//

import Combine
import Foundation
import Shared

@MainActor
class BookmarkViewModel: ObservableObject {
  struct BookmarkItem: Identifiable, Equatable {
    let id: String
    let page: Int
    let lastUpdated: Date
  }
  
  @Published var bookmarks: [BookmarkItem] = []
  
  private let databaseManager = DatabaseManager.shared

  func observeBookmarks() async {
    do {
      let sequence = databaseManager.bookmarksSequence()
      for try await sharedBookmarks in sequence {
        bookmarks = sharedBookmarks.map(Self.mapToItem)
      }
    } catch is CancellationError {
      // SwiftUI cancels the task when the view disappears; ignore.
    } catch {
      print("Failed to observe bookmarks: \(error)")
    }
  }
  
  func addRandomBookmark() async throws {
     try await databaseManager.addRandomBookmark()
  }
  
  // Format bookmark for display
  func formatBookmark(_ bookmark: BookmarkItem) -> String {
    "Page \(bookmark.page)"
  }
  
  // Format the timestamp
  func formatTimestamp(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .short
    return formatter.string(from: date)
  }
  
  private static func mapToItem(_ bookmark: PageBookmark) -> BookmarkItem {
    let identifier = bookmark.localId ?? "page-\(bookmark.page)"
    let lastUpdatedDate = bookmark.lastUpdated
    return BookmarkItem(id: identifier, page: Int(bookmark.page), lastUpdated: lastUpdatedDate)
  }
}

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
  private var bookmarksTask: Task<Void, Never>?
  
  init() {
    observeBookmarks()
  }
  
  deinit {
    bookmarksTask?.cancel()
  }
  
  private func observeBookmarks() {
    bookmarksTask?.cancel()
    bookmarksTask = Task {
      do {
        let sequence = databaseManager.bookmarksSequence()
        for try await sharedBookmarks in sequence {
          bookmarks = sharedBookmarks.map(Self.mapToItem)
        }
      } catch {
        print("Failed to observe bookmarks: \(error)")
      }
    }
  }
  
  func addRandomBookmark() {
    Task {
      do {
        try await databaseManager.addRandomBookmark()
      } catch {
        print("Failed to add random bookmark: \(error)")
      }
    }
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
    let milliseconds = bookmark.lastUpdated.toEpochMilliseconds()
    let lastUpdatedDate = Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000.0)
    return BookmarkItem(id: identifier, page: Int(bookmark.page), lastUpdated: lastUpdatedDate)
  }
}

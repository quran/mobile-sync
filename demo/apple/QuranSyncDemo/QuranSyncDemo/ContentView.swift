//
//  ContentView.swift
//  QuranSyncDemo
//
//  Created by Ahmed El-Helw on 5/18/25.
//

import SwiftUI
import Shared

struct ContentView: View {
  @StateObject private var viewModel = BookmarkViewModel()
  
  var body: some View {
    NavigationView {
      VStack {
        if viewModel.bookmarks.isEmpty {
          ContentUnavailableView(
            "No Bookmarks",
            systemImage: "bookmark.slash",
            description: Text("Tap the button below to add a random bookmark")
          )
        } else {
          List {
            ForEach(viewModel.bookmarks, id: \.id) { bookmark in
              VStack(alignment: .leading) {
                Text(viewModel.formatBookmark(bookmark))
                  .font(.headline)
                
                Text("Updated: \(viewModel.formatTimestamp(bookmark.lastUpdated))")
                  .font(.caption)
                  .foregroundColor(.secondary)
              }
              .padding(.vertical, 4)
            }
          }
        }
        
        Button(action: {
          Task {
            do {
              try await viewModel.addRandomBookmark()
            } catch {
              print("Failed to add random bookmark: \(error)")
            }
          }
        }) {
          Label("Add Random Bookmark", systemImage: "bookmark.fill")
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .padding()
      }
      .navigationTitle("Quran Bookmarks")
    }
    .task {
      await viewModel.observeBookmarks()
    }
  }
}

#Preview {
  ContentView()
}

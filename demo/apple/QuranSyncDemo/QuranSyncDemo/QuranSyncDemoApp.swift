//
//  QuranSyncDemoApp.swift
//  QuranSyncDemo
//
//  Created by Ahmed El-Helw on 5/18/25.
//

import SwiftUI
import Shared

@main
struct QuranSyncDemoApp: App {
  // Initialize the database manager when the app starts
  init() {
    // Access the shared instance to ensure it's initialized on app startup
    let _ = DatabaseManager.shared
  }
  
  var body: some Scene {
    WindowGroup {
      ContentView()
    }
  }
}

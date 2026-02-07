import SwiftUI
import Shared
import KMPNativeCoroutinesAsync

/**
 * Authentication screen for iOS demo app.
 */
struct AuthView: View {
    @ObservedObject var viewModel: SyncViewModel
    
    var onAuthenticationSuccess: () -> Void = {}

    var body: some View {
        ZStack {
            // Background
            Color(.systemBackground)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                // Header (Only show when not in success state or combine with success)
                if !(viewModel.authState is Shared.AuthState.Success) {
                    VStack(spacing: 8) {
                        Text("Quran.com Sync")
                            .font(.largeTitle)
                            .fontWeight(.bold)

                        Text("Sign in with Quran Foundation")
                            .font(.body)
                            .foregroundColor(.secondary)
                    }
                    .padding(.top, 32)
                    .padding(.bottom, 32)
                }

                // Content based on auth state
                Group {
                    let state = viewModel.authState
                    if state is Shared.AuthState.Idle {
                        loginButtonContent
                            .padding(.horizontal, 16)
                    } else if state is Shared.AuthState.Loading {
                        loadingContent
                    } else if let successState = state as? Shared.AuthState.Success {
                        SuccessTabView(viewModel: viewModel, userInfo: successState.userInfo)
                    } else if state is Shared.AuthState.Error {
                        errorContent
                            .padding(.horizontal, 16)
                    }
                }

                Spacer()
            }
        }
        .onChange(of: viewModel.authState) { _, newState in
            if newState is Shared.AuthState.Success {
                onAuthenticationSuccess()
            }
        }
        .task {
            // Observe data flows in the background
            await viewModel.observeData()
        }
    }

    // MARK: - Content Views

    private var loginButtonContent: some View {
        VStack(spacing: 16) {
            Button(action: {
                Task {
                    try? await viewModel.login()
                }
            }) {
                Text("Sign in with OAuth")
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Color.accentColor)
                    .cornerRadius(8)
            }

            Text("You will be redirected to Quran Foundation to securely sign in.")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private var loadingContent: some View {
        VStack(spacing: 16) {
            ProgressView()
                .scaleEffect(1.5, anchor: .center)
                .padding()

            Text("Signing in...")
                .font(.body)
        }
    }

    private var errorContent: some View {
        VStack(spacing: 16) {
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 60))
                .foregroundColor(.red)

            Text("Authentication Failed")
                .font(.headline)

            if let errorState = viewModel.authState as? Shared.AuthState.Error {
                Text(errorState.message)
                    .font(.caption)
                    .foregroundColor(.red)
                    .padding(12)
                    .background(Color(.systemRed).opacity(0.1))
                    .cornerRadius(8)
                    .multilineTextAlignment(.center)
            }

            HStack(spacing: 12) {
                Button("Dismiss") {
                    viewModel.clearError()
                }
                .buttonStyle(.bordered)

                Button("Retry") {
                    Task {
                        try? await viewModel.login()
                    }
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding()
    }
}

struct SuccessTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    let userInfo: Shared.UserInfo
    @State private var selectedTab = 0
    
    var body: some View {
        VStack(spacing: 0) {
            // Mini Header
            HStack {
                VStack(alignment: .leading) {
                    Text("Welcome, \(userInfo.displayName ?? "User")!")
                        .font(.headline)
                    if let email = userInfo.email {
                        Text(email)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                Spacer()
                Button(action: {
                    Task {
                        try? await viewModel.logout()
                    }
                }) {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                        .foregroundColor(.red)
                }
            }
            .padding()
            .background(Color(.secondarySystemBackground))

            TabView(selection: $selectedTab) {
                BookmarksTabView(viewModel: viewModel)
                    .tabItem {
                        Label("Bookmarks", systemImage: "bookmark")
                    }
                    .tag(0)

                CollectionsTabView(viewModel: viewModel)
                    .tabItem {
                        Label("Collections", systemImage: "folder")
                    }
                    .tag(1)

                NotesTabView(viewModel: viewModel)
                    .tabItem {
                        Label("Notes", systemImage: "note.text")
                    }
                    .tag(2)
            }
        }
    }
}

struct BookmarksTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    
    private let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()

    var body: some View {
        List {
            Section(header: HStack {
                Text("Your Bookmarks")
                Spacer()
                HStack(spacing: 16) {
                    Button(action: {
                        let randomPage = Shared.QuranActionsUtils().getRandomPage()
                        viewModel.addBookmark(page: randomPage)
                    }) {
                        Image(systemName: "plus.app")
                    }
                    Button(action: {
                        let sura = Shared.QuranActionsUtils().getRandomSura()
                        let ayah = Shared.QuranActionsUtils().getRandomAyah(sura: sura)
                        viewModel.addBookmark(sura: sura, ayah: ayah)
                    }) {
                        Image(systemName: "plus.square")
                    }
                }
            }) {
                if viewModel.bookmarks.isEmpty {
                    Text("No bookmarks yet.")
                        .foregroundColor(.secondary)
                        .italic()
                } else {
                    ForEach(viewModel.bookmarks, id: \.self) { bookmark in
                        HStack {
                            Image(systemName: "bookmark.fill")
                                .foregroundColor(.accentColor)
                            
                            VStack(alignment: .leading) {
                                if let pageBookmark = bookmark as? Shared.Bookmark.PageBookmark {
                                    Text("Page \(pageBookmark.page)")
                                        .font(.body)
                                } else if let ayahBookmark = bookmark as? Shared.Bookmark.AyahBookmark {
                                    Text("Surah \(ayahBookmark.sura), Ayah \(ayahBookmark.ayah)")
                                        .font(.body)
                                }
                                
                                let date = (bookmark as? Shared.Bookmark.PageBookmark)?.lastUpdated ?? (bookmark as? Shared.Bookmark.AyahBookmark)?.lastUpdated
                                if let date = date {
                                    Text("\(dateFormatter.string(from: date))")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                            }
                            
                            Spacer()
                            
                            Button(action: {
                                viewModel.deleteBookmark(bookmark: bookmark)
                            }) {
                                Image(systemName: "trash")
                                    .foregroundColor(.red)
                            }
                        }
                    }
                }
            }
        }
    }
}

struct CollectionsTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    @State private var showAddDialog = false
    @State private var newCollectionName = ""
    
    var body: some View {
        List {
            Section(header: HStack {
                Text("Your Collections")
                Spacer()
                Button(action: { showAddDialog = true }) {
                    Image(systemName: "plus.rectangle.on.folder")
                }
            }) {
                if viewModel.collections.isEmpty {
                    Text("No collections yet.")
                        .foregroundColor(.secondary)
                        .italic()
                } else {
                    ForEach(viewModel.collections, id: \.localId) { collection in
                        CollectionRowView(viewModel: viewModel, collection: collection)
                    }
                }
            }
        }
        .alert("New Collection", isPresented: $showAddDialog) {
            TextField("Name", text: $newCollectionName)
            Button("Add") {
                if !newCollectionName.isEmpty {
                    viewModel.addCollection(name: newCollectionName)
                    newCollectionName = ""
                }
            }
            Button("Cancel", role: .cancel) {
                newCollectionName = ""
            }
        }
    }
}

struct CollectionRowView: View {
    @ObservedObject var viewModel: SyncViewModel
    let collection: Shared.Collection_
    @State private var bookmarks: [Shared.CollectionBookmark] = []
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading) {
            HStack {
                Image(systemName: "folder.fill")
                    .foregroundColor(.orange)
                Text(collection.name)
                    .font(.headline)
                Spacer()
                Button(action: { viewModel.deleteCollection(localId: collection.localId) }) {
                    Image(systemName: "trash")
                        .foregroundColor(.red)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                withAnimation {
                    isExpanded.toggle()
                }
            }

            if isExpanded {
                VStack(alignment: .leading, spacing: 8) {
                    if bookmarks.isEmpty {
                        Text("No bookmarks in this collection")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .padding(.leading, 32)
                    } else {
                        ForEach(bookmarks, id: \.localId) { cb in
                            HStack {
                                Image(systemName: "bookmark")
                                    .font(.caption)
                                Text(displayText(for: cb))
                                    .font(.subheadline)
                            }
                            .padding(.leading, 32)
                        }
                    }
                }
                .padding(.top, 8)
            }
        }
        .task {
            do {
                for try await list in viewModel.bookmarksForCollection(collectionId: collection.localId) {
                    self.bookmarks = list as? [Shared.CollectionBookmark] ?? []
                }
            } catch {
                print("Error observing bookmarks for collection: \(error)")
            }
        }
    }
    
    private func displayText(for cb: Shared.CollectionBookmark) -> String {
        if let pb = cb as? Shared.CollectionBookmark.PageBookmark {
            return "Page \(pb.page)"
        } else if let ab = cb as? Shared.CollectionBookmark.AyahBookmark {
            return "Sura \(ab.sura), Ayah \(ab.ayah)"
        }
        return ""
    }
}

struct NotesTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    @State private var showAddDialog = false
    @State private var newNoteBody = ""

    var body: some View {
        List {
            Section(header: HStack {
                Text("Your Notes")
                Spacer()
                Button(action: { showAddDialog = true }) {
                    Image(systemName: "square.and.pencil")
                }
            }) {
                if viewModel.notes.isEmpty {
                    Text("No notes yet.")
                        .foregroundColor(.secondary)
                        .italic()
                } else {
                    ForEach(viewModel.notes, id: \.localId) { note in
                        HStack {
                            Image(systemName: "note.text")
                                .foregroundColor(.blue)
                            VStack(alignment: .leading) {
                                Text(note.body ?? "")
                                    .font(.body)
                                Text("Ayah \(note.startAyahId) - \(note.endAyahId)")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Button(action: { viewModel.deleteNote(localId: note.localId) }) {
                                Image(systemName: "trash")
                                    .foregroundColor(.red)
                            }
                        }
                    }
                }
            }
        }
        .alert("New Note", isPresented: $showAddDialog) {
            TextField("Content", text: $newNoteBody)
            Button("Add") {
                if !newNoteBody.isEmpty {
                    // Using dummy range 1-1 for now like Android
                    viewModel.addNote(body: newNoteBody, startAyahId: 1, endAyahId: 1)
                    newNoteBody = ""
                }
            }
            Button("Cancel", role: .cancel) {
                newNoteBody = ""
            }
        }
    }
}

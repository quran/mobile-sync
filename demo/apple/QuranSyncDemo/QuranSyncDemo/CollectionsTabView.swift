import SwiftUI
import Shared

struct CollectionsTabView: View {
    @ObservedObject var viewModel: SyncViewModel
    @State private var showAddDialog = false
    @State private var showSelectCollectionSheet = false
    @State private var newCollectionName = ""
    @State private var selectedBookmarkType: SelectedBookmarkType = .page
    
    enum SelectedBookmarkType {
        case page, ayah
    }
    
    var body: some View {
        List {
            Section(header: HStack {
                Text("Your Collections")
                Spacer()
                HStack(spacing: 16) {
                    Button(action: { 
                        selectedBookmarkType = .ayah
                        showSelectCollectionSheet = true 
                    }) {
                        Image(systemName: "plus.square")
                    }
                    Button(action: { showAddDialog = true }) {
                        Image(systemName: "folder.badge.plus")
                    }
                }
            }) {
                if viewModel.collectionsWithBookmarks.isEmpty {
                    Text("No collections yet.")
                        .foregroundColor(.secondary)
                        .italic()
                } else {
                    ForEach(viewModel.collectionsWithBookmarks, id: \.collection.localId) { collectionWithBookmarks in
                        CollectionRowView(viewModel: viewModel, collectionWithBookmarks: collectionWithBookmarks)
                    }
                }
            }
        }
        .alert("New Collection", isPresented: $showAddDialog) {
            TextField("Name", text: $newCollectionName)
            Button("Add") {
                let name = newCollectionName
                if !name.isEmpty {
                    Task {
                        await viewModel.addCollection(name: name)
                    }
                }
                newCollectionName = ""
            }
            Button("Cancel", role: .cancel) {
                newCollectionName = ""
            }
        }
        .sheet(isPresented: $showSelectCollectionSheet) {
            NavigationView {
                List(viewModel.collectionsWithBookmarks, id: \.collection.localId) { item in
                    Button(action: {
                        Task {
                            let sura = Shared.QuranActionsUtils().getRandomSura()
                            let ayah = Shared.QuranActionsUtils().getRandomAyah(sura: sura)
                            if let bookmark = await viewModel.addBookmark(sura: sura, ayah: ayah) {
                                await viewModel.addBookmarkToCollection(collectionId: item.collection.localId, bookmark: bookmark)
                            }
                        }
                        showSelectCollectionSheet = false
                    }) {
                        Text(item.collection.name)
                    }
                }
                .navigationTitle("Select Collection")
                .navigationBarItems(trailing: Button("Close") {
                    showSelectCollectionSheet = false
                })
            }
        }
    }
}

struct CollectionRowView: View {
    @ObservedObject var viewModel: SyncViewModel
    let collectionWithBookmarks: Shared.CollectionWithBookmarks
    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading) {
            HStack {
                HStack {
                    Image(systemName: "folder.fill")
                        .foregroundColor(.orange)
                    Text(collectionWithBookmarks.collection.name)
                        .font(.headline)
                    Spacer()
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation {
                        isExpanded.toggle()
                    }
                }
                
                Button(action: { 
                    Task {
                        await viewModel.deleteCollection(collectionId: collectionWithBookmarks.collection.localId)
                    }
                }) {
                    Image(systemName: "trash")
                        .foregroundColor(.red)
                }
                .buttonStyle(BorderlessButtonStyle())
            }

            if isExpanded {
                VStack(alignment: .leading, spacing: 8) {
                    if collectionWithBookmarks.bookmarks.isEmpty {
                        Text("No bookmarks in this collection")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .padding(.leading, 32)
                    } else {
                        ForEach(collectionWithBookmarks.bookmarks, id: \.localId) { cb in
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
